package com.hdsl.server;

import com.hdsl.server.api.ApiException;
import com.hdsl.server.job.JobManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JobManager：单变更任务（忙时 409）、完成、取消、超时与终态事件。 */
@SpringBootTest
class JobManagerTest {

    private static final Path DATA_ROOT = TestDataRoots.prepare("jobs");

    private static final long WAIT_MILLIS = 10_000L;

    @DynamicPropertySource
    static void dataRoot(DynamicPropertyRegistry registry) {
        registry.add("pcl.data-root", DATA_ROOT::toString);
    }

    @Autowired
    private JobManager jobs;

    @Test
    void busyJobRejectsSecondSubmissionAndCompletes() throws Exception {
        awaitIdle();
        CountDownLatch release = new CountDownLatch(1);
        JobManager.View first = jobs.submit("TEST", Duration.ofSeconds(30), context -> {
            context.log("等待测试释放");
            context.phase("进行中");
            context.checkCancelled();
            release.await();
            return Map.of("value", 42);
        });
        assertEquals(JobManager.State.RUNNING, first.state());

        ApiException busy = assertThrows(ApiException.class,
                () -> jobs.submit("TEST", Duration.ofSeconds(30), context -> null));
        assertEquals("CONFLICT_BUSY", busy.code());
        assertEquals(409, busy.status().value());
        assertThrows(ApiException.class, jobs::ensureIdle);

        release.countDown();
        JobManager.View finished = awaitState(first.id(), JobManager.State.SUCCEEDED);
        assertNotNull(finished.finishedAt());
        assertNull(finished.error());
        assertEquals(42, ((Number) ((Map<?, ?>) finished.result()).get("value")).intValue());
        awaitIdle();

        // 完成后可再次提交
        JobManager.View second = jobs.submit("TEST", Duration.ofSeconds(30), context -> Map.of("ok", true));
        assertEquals(JobManager.State.RUNNING, second.state());
        assertEquals(JobManager.State.SUCCEEDED, awaitState(second.id(), JobManager.State.SUCCEEDED).state());
        awaitIdle();
    }

    @Test
    void cancelStopsRunningJobAndPublishesTerminalState() throws Exception {
        awaitIdle();
        JobManager.View running = jobs.submit("CANCEL_TEST", Duration.ofSeconds(30), context -> {
            while (true) {
                context.checkCancelled();
                context.log("工作中");
                Thread.sleep(20L);
            }
        });
        JobManager.View cancelling = jobs.cancel(running.id());
        assertEquals(JobManager.State.RUNNING, cancelling.state());
        JobManager.View terminal = awaitState(running.id(), JobManager.State.CANCELLED);
        assertNotNull(terminal.finishedAt());
        assertEquals("已取消", terminal.phase());
        awaitIdle();
    }

    @Test
    void timeoutFailsJobWithTimeoutCode() throws Exception {
        awaitIdle();
        JobManager.View timed = jobs.submit("TIMEOUT_TEST", Duration.ofMillis(200), context -> {
            while (true) {
                context.checkCancelled();
                Thread.sleep(20L);
            }
        });
        JobManager.View terminal = awaitState(timed.id(), JobManager.State.FAILED);
        assertNotNull(terminal.error());
        assertEquals("TIMEOUT", terminal.error().code());
        awaitIdle();
    }

    @Test
    void unknownJobReturnsNotFound() {
        ApiException notFound = assertThrows(ApiException.class, () -> jobs.view("j-not-exists"));
        assertEquals("NOT_FOUND", notFound.code());
        assertEquals(404, notFound.status().value());
    }

    private JobManager.View awaitState(String id, JobManager.State expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        JobManager.View view = jobs.view(id);
        while (view.state() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
            view = jobs.view(id);
        }
        assertEquals(expected, view.state(), "任务未在预期时间内到达终态");
        return view;
    }

    private void awaitIdle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while (jobs.busy() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertFalse(jobs.busy(), "上一个任务结束后应释放单任务锁");
    }
}
