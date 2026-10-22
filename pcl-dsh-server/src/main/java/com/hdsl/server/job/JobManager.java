package com.hdsl.server.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hdsl.server.api.ApiException;
import com.hdsl.server.config.PclProperties;
import com.hdsl.server.util.LogSanitizer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单变更任务调度 + per-job SSE（方案 §4.3）。
 *
 * <ul>
 *   <li>同一时间只允许 1 个变更任务；忙时 submit / 同步写操作返回 409 CONFLICT_BUSY，不排队。</li>
 *   <li>状态机：RUNNING →（SUCCEEDED | FAILED | CANCELLED），不设排队队列。</li>
 *   <li>事件：state/log/result/error；15 秒心跳；断线用 Last-Event-ID 续传（保留最近事件）。</li>
 *   <li>取消：协作式（{@link Context#checkCancelled()}）+ 中断工作线程，取消后必须发送终态。</li>
 * </ul>
 *
 * <p>后续增量的 Controller 只需调用 {@link #submit(String, Duration, Task)} 即可接入长任务。</p>
 */
@Component
public class JobManager {

    private static final Logger log = LoggerFactory.getLogger(JobManager.class);
    private static final int MAX_EVENTS = 4000;
    private static final int MAX_JOBS = 50;
    private static final int RECENT_JOBS = 20;

    public enum State {
        RUNNING, SUCCEEDED, FAILED, CANCELLED
    }

    /** 长任务入口：在独立虚拟线程中执行，通过 {@link Context} 上报日志/阶段并响应取消。 */
    @FunctionalInterface
    public interface Task {
        Object run(Context context) throws Exception;
    }

    public interface Context {
        String jobId();

        void log(String line);

        void phase(String phase);

        /** 协作式取消检查；取消或线程被中断时抛出 InterruptedException。 */
        void checkCancelled() throws InterruptedException;
    }

    public record Error(String code, String message, Map<String, Object> details) {
        public Error {
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }

    public record View(String id, String type, State state, String phase, String message, long createdAt,
                       Long startedAt, Long finishedAt, long lastSeq, Object result, Error error) {
    }

    public record GlobalStatus(String code, String message) {
    }

    private record Event(long seq, String name, String data) {
    }

    private final ObjectMapper json;
    private final PclProperties properties;
    private final AtomicReference<Job> active = new AtomicReference<>();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler;

    public JobManager(ObjectMapper json, PclProperties properties) {
        this.json = json;
        this.properties = properties;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pcl-job-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        long heartbeatMillis = Math.max(1000L, properties.getSseHeartbeat().toMillis());
        this.scheduler.scheduleAtFixedRate(this::heartbeat, heartbeatMillis, heartbeatMillis,
                TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------ 对外 API

    /** 提交唯一变更任务；已有任务在跑时抛出 409 CONFLICT_BUSY（不排队）。 */
    public View submit(String type, Duration timeout, Task task) {
        Objects.requireNonNull(task, "task");
        Job job = new Job(type);
        if (!active.compareAndSet(null, job)) {
            throw ApiException.conflictBusy();
        }
        boolean started = false;
        try {
            jobs.put(job.id, job);
            purgeHistory();
            synchronized (job) {
                emitStateLocked(job, "任务已提交，开始执行。");
            }
            job.runner = Thread.ofVirtual().name("pcl-job-" + job.id).unstarted(() -> execute(job, task));
            Duration effective = timeout == null || timeout.isZero() || timeout.isNegative()
                    ? properties.getJobTimeout() : timeout;
            if (effective != null && !effective.isNegative() && !effective.isZero()) {
                job.watchdog = scheduler.schedule(() -> onTimeout(job), effective.toMillis(), TimeUnit.MILLISECONDS);
            }
            job.runner.start();
            started = true;
            return view(job);
        } finally {
            if (!started) {
                scheduledCancel(job);
                active.compareAndSet(job, null);
                jobs.remove(job.id);
            }
        }
    }

    /** 同步写操作进入前的忙检查（方案 §4.2：忙时 409）。 */
    public void ensureIdle() {
        if (active.get() != null) {
            throw ApiException.conflictBusy();
        }
    }

    public boolean busy() {
        return active.get() != null;
    }

    public GlobalStatus globalStatus() {
        Job job = active.get();
        if (job == null) {
            return new GlobalStatus("READY", "就绪");
        }
        String phase;
        synchronized (job) {
            phase = job.phase == null || job.phase.isBlank() ? job.message : job.phase;
        }
        return new GlobalStatus("RUNNING", "进行中：" + phase);
    }

    public View view(String id) {
        return view(require(id));
    }

    public List<View> recent() {
        return jobs.values().stream()
                .sorted(Comparator.comparingLong((Job job) -> job.createdAt).reversed())
                .limit(RECENT_JOBS)
                .map(this::view)
                .toList();
    }

    /** 订阅任务 SSE；lastEventId &gt; 0 时先补发其后的事件（Last-Event-ID 续传）。 */
    public SseEmitter subscribe(String id, long lastEventId) {
        Job job = require(id);
        long timeoutMillis = properties.getSseTimeout() == null ? 0L : properties.getSseTimeout().toMillis();
        SseEmitter emitter = new SseEmitter(timeoutMillis <= 0 ? 0L : timeoutMillis);
        emitter.onCompletion(() -> removeEmitter(job, emitter));
        emitter.onTimeout(() -> removeEmitter(job, emitter));
        emitter.onError(error -> removeEmitter(job, emitter));
        synchronized (job) {
            try {
                for (Event event : List.copyOf(job.events)) {
                    if (event.seq() > lastEventId) {
                        send(emitter, event);
                    }
                }
            } catch (IOException | RuntimeException failure) {
                emitter.completeWithError(failure);
                return emitter;
            }
            if (job.state == State.RUNNING) {
                job.emitters.add(emitter);
            } else {
                emitter.complete();
            }
        }
        return emitter;
    }

    /** 请求取消；未知任务 404，已结束任务 409。 */
    public View cancel(String id) {
        Job job = require(id);
        Thread runner;
        synchronized (job) {
            if (job.state != State.RUNNING) {
                throw new ApiException("CONFLICT_JOB_FINISHED", HttpStatus.CONFLICT, "任务已结束，无需取消。");
            }
            job.cancelRequested = true;
            job.phase = "取消中";
            emitStateLocked(job, "已请求取消当前任务，正在收尾。");
            runner = job.runner;
        }
        if (runner != null) {
            runner.interrupt();
        }
        log.info("已请求取消任务：{}", job.id);
        return view(job);
    }

    // ------------------------------------------------------------------ 执行

    private void execute(Job job, Task task) {
        job.startedAt = System.currentTimeMillis();
        job.runner = Thread.currentThread();
        try {
            Object result = task.run(new ContextImpl(job));
            if (job.cancelRequested) {
                finishCancelled(job);
            } else if (job.timedOut) {
                finishTimeout(job);
            } else {
                finishSucceeded(job, result);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (job.cancelRequested) {
                finishCancelled(job);
            } else if (job.timedOut) {
                finishTimeout(job);
            } else {
                finishFailed(job, "INTERRUPTED", "任务被中断。");
            }
        } catch (Throwable failure) {
            if (job.cancelRequested) {
                finishCancelled(job);
            } else if (job.timedOut) {
                finishTimeout(job);
            } else if (failure instanceof ApiException apiException) {
                finishFailed(job, apiException.code(), apiException.getMessage());
            } else {
                finishFailed(job, "INTERNAL", "任务失败："
                        + LogSanitizer.sanitizeLine(Objects.toString(failure.getMessage(),
                        failure.getClass().getSimpleName()), 300));
            }
        } finally {
            job.runner = null;
            scheduledCancel(job);
            active.compareAndSet(job, null);
        }
    }

    private void finishSucceeded(Job job, Object result) {
        boolean finished = false;
        synchronized (job) {
            if (job.state != State.RUNNING) {
                return;
            }
            job.state = State.SUCCEEDED;
            job.phase = "完成";
            job.finishedAt = System.currentTimeMillis();
            job.result = result;
            emitStateLocked(job, "任务完成。");
            ObjectNode data = json.createObjectNode();
            data.put("jobId", job.id);
            data.put("state", job.state.name());
            if (result == null) {
                data.putNull("result");
            } else {
                data.set("result", json.valueToTree(result));
            }
            dispatchLocked(job, "result", data);
            completeEmittersLocked(job);
            finished = true;
        }
        if (finished) {
            log.info("任务完成：{}（{}）", job.id, job.type);
        }
    }

    private void finishFailed(Job job, String code, String message) {
        boolean finished = false;
        synchronized (job) {
            if (job.state != State.RUNNING) {
                return;
            }
            job.state = State.FAILED;
            job.phase = "未完成";
            job.finishedAt = System.currentTimeMillis();
            job.error = new Error(code, message, Map.of());
            emitStateLocked(job, "任务失败：" + message);
            ObjectNode data = json.createObjectNode();
            data.put("jobId", job.id);
            data.put("state", job.state.name());
            data.put("code", code);
            data.put("message", message);
            data.set("details", json.createObjectNode());
            dispatchLocked(job, "error", data);
            completeEmittersLocked(job);
            finished = true;
        }
        if (finished) {
            log.warn("任务失败：{}（{}）{}", job.id, job.type, message);
        }
    }

    private void finishCancelled(Job job) {
        synchronized (job) {
            if (job.state != State.RUNNING) {
                return;
            }
            job.state = State.CANCELLED;
            job.phase = "已取消";
            job.finishedAt = System.currentTimeMillis();
            emitStateLocked(job, "任务已取消。");
            completeEmittersLocked(job);
        }
        log.info("任务已取消：{}（{}）", job.id, job.type);
    }

    private void finishTimeout(Job job) {
        synchronized (job) {
            if (job.state != State.RUNNING) {
                return;
            }
            job.state = State.FAILED;
            job.phase = "超时";
            job.finishedAt = System.currentTimeMillis();
            job.error = new Error("TIMEOUT", "任务执行超时，已终止等待。", Map.of());
            emitStateLocked(job, job.error.message());
            ObjectNode data = json.createObjectNode();
            data.put("jobId", job.id);
            data.put("state", job.state.name());
            data.put("code", "TIMEOUT");
            data.put("message", job.error.message());
            data.set("details", json.createObjectNode());
            dispatchLocked(job, "error", data);
            completeEmittersLocked(job);
        }
        log.warn("任务超时：{}（{}）", job.id, job.type);
    }

    private void onTimeout(Job job) {
        synchronized (job) {
            if (job.state != State.RUNNING || job.timedOut) {
                return;
            }
            job.timedOut = true;
            job.phase = "超时";
            emitStateLocked(job, "任务执行超时，正在终止。");
        }
        Thread runner = job.runner;
        if (runner != null) {
            runner.interrupt();
        }
    }

    // ------------------------------------------------------------------ 事件 / SSE

    private void emitStateLocked(Job job, String message) {
        if (message != null) {
            job.message = message;
        }
        ObjectNode data = json.createObjectNode();
        data.put("state", job.state.name());
        data.put("phase", job.phase == null ? "" : job.phase);
        data.put("message", job.message == null ? "" : job.message);
        dispatchLocked(job, "state", data);
    }

    private void emitLogLocked(Job job, String line, String level) {
        ObjectNode data = json.createObjectNode();
        data.put("level", level == null ? "info" : level);
        data.put("line", line == null ? "" : line);
        dispatchLocked(job, "log", data);
    }

    /** 调用方必须持有 job 锁。 */
    private void dispatchLocked(Job job, String name, ObjectNode data) {
        job.seq++;
        data.put("jobId", job.id);
        data.put("seq", job.seq);
        data.put("ts", System.currentTimeMillis());
        Event event = new Event(job.seq, name, data.toString());
        job.events.add(event);
        if (job.events.size() > MAX_EVENTS) {
            job.events.subList(0, job.events.size() - MAX_EVENTS).clear();
        }
        Iterator<SseEmitter> iterator = job.emitters.iterator();
        while (iterator.hasNext()) {
            SseEmitter emitter = iterator.next();
            try {
                send(emitter, event);
            } catch (IOException | RuntimeException failure) {
                iterator.remove();
                try {
                    emitter.complete();
                } catch (RuntimeException ignored) {
                    // 客户端已断开。
                }
            }
        }
    }

    private void send(SseEmitter emitter, Event event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(Long.toString(event.seq()))
                .name(event.name())
                .data(event.data(), MediaType.APPLICATION_JSON));
    }

    private void completeEmittersLocked(Job job) {
        for (SseEmitter emitter : List.copyOf(job.emitters)) {
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // 客户端已断开。
            }
        }
        job.emitters.clear();
    }

    private void removeEmitter(Job job, SseEmitter emitter) {
        synchronized (job) {
            job.emitters.remove(emitter);
        }
    }

    private void heartbeat() {
        try {
            for (Job job : jobs.values()) {
                synchronized (job) {
                    if (job.emitters.isEmpty()) {
                        continue;
                    }
                    Iterator<SseEmitter> iterator = job.emitters.iterator();
                    while (iterator.hasNext()) {
                        SseEmitter emitter = iterator.next();
                        try {
                            emitter.send(SseEmitter.event().comment("ping"));
                        } catch (IOException | RuntimeException failure) {
                            iterator.remove();
                            try {
                                emitter.complete();
                            } catch (RuntimeException ignored) {
                                // 客户端已断开。
                            }
                        }
                    }
                }
            }
        } catch (RuntimeException failure) {
            log.debug("SSE 心跳发送异常：{}", failure.toString());
        }
    }

    // ------------------------------------------------------------------ 内部工具

    private View view(Job job) {
        synchronized (job) {
            return new View(job.id, job.type, job.state, job.phase, job.message, job.createdAt,
                    job.startedAt == 0 ? null : job.startedAt,
                    job.finishedAt == 0 ? null : job.finishedAt,
                    job.seq, job.result, job.error);
        }
    }

    private Job require(String id) {
        Job job = id == null ? null : jobs.get(id);
        if (job == null) {
            throw ApiException.notFound("任务不存在或已被清理。");
        }
        return job;
    }

    private void purgeHistory() {
        int excess = jobs.size() - MAX_JOBS;
        if (excess <= 0) {
            return;
        }
        List<Job> removable = jobs.values().stream()
                .filter(job -> {
                    synchronized (job) {
                        return job.state != State.RUNNING;
                    }
                })
                .sorted(Comparator.comparingLong((Job job) -> job.createdAt))
                .limit(excess)
                .toList();
        for (Job job : removable) {
            jobs.remove(job.id);
        }
    }

    private void scheduledCancel(Job job) {
        ScheduledFuture<?> watchdog = job.watchdog;
        if (watchdog != null) {
            watchdog.cancel(false);
        }
    }

    @PreDestroy
    public void shutdown() {
        Job running = active.get();
        if (running != null) {
            try {
                cancel(running.id);
            } catch (RuntimeException ignored) {
                // 关闭路径尽力而为。
            }
        }
        for (Job job : jobs.values()) {
            synchronized (job) {
                completeEmittersLocked(job);
            }
        }
        scheduler.shutdownNow();
        workers.shutdownNow();
    }

    private final class ContextImpl implements Context {
        private final Job job;

        private ContextImpl(Job job) {
            this.job = job;
        }

        @Override
        public String jobId() {
            return job.id;
        }

        @Override
        public void log(String line) {
            String safe = LogSanitizer.sanitizeLine(line, 2000);
            synchronized (job) {
                emitLogLocked(job, safe, "info");
            }
        }

        @Override
        public void phase(String phase) {
            String safe = LogSanitizer.sanitizeLine(phase, 120);
            if (safe.isBlank()) {
                return;
            }
            synchronized (job) {
                job.phase = safe;
                emitStateLocked(job, job.message);
            }
        }

        @Override
        public void checkCancelled() throws InterruptedException {
            if (job.cancelRequested || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("任务已取消");
            }
        }
    }

    private final class Job {
        private final String id = "j-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        private final String type;
        private final long createdAt = System.currentTimeMillis();
        private State state = State.RUNNING;
        private long startedAt;
        private long finishedAt;
        private String phase = "开始";
        private String message = "任务已提交。";
        private Object result;
        private Error error;
        private boolean cancelRequested;
        private boolean timedOut;
        private Thread runner;
        private ScheduledFuture<?> watchdog;
        private long seq;
        private final List<Event> events = new ArrayList<>();
        private final List<SseEmitter> emitters = new ArrayList<>();

        private Job(String type) {
            this.type = type == null || type.isBlank() ? "GENERIC" : type;
        }
    }
}
