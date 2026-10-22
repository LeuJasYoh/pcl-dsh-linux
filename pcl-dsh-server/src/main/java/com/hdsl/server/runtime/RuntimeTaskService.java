package com.hdsl.server.runtime;

import com.hdsl.core.Instance;
import com.hdsl.core.InstanceStore;
import com.hdsl.runtime.Capabilities;
import com.hdsl.runtime.RuntimeService;
import com.hdsl.runtime.SemVer;
import com.hdsl.server.api.ApiDtos;
import com.hdsl.server.api.ApiException;
import com.hdsl.server.config.DataRoot;
import com.hdsl.server.config.RuntimeProvider;
import com.hdsl.server.job.JobManager;
import com.hdsl.server.log.LogFiles;
import com.hdsl.server.security.AuditLog;
import com.hdsl.server.util.LogSanitizer;
import com.hdsl.server.util.Trash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 运行时接口业务（方案 §4.2 / §4.3）：可用版本查询、安装/探测长任务、删除（引用保护）。
 *
 * <p>安装与探测统一走 {@link JobManager}（安装 20 分钟、探测 90 秒超时），日志经
 * {@code ctx.log} 输出到 SSE 与 data/logs；能力摘要写入 {@link RuntimeCompatibility}，
 * 供 GET /api/runtimes 的 compatibility 字段展示。</p>
 */
@Component
public class RuntimeTaskService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeTaskService.class);
    private static final String DEFAULT_UPSTREAM_MESSAGE = "版本查询失败，请检查下载源或代理后重试。";

    private final RuntimeProvider runtimes;
    private final InstanceStore store;
    private final JobManager jobs;
    private final DataRoot data;
    private final AuditLog audit;
    private final RuntimeCompatibility compatibility;
    private final LogFiles logs;

    private final Object versionQueryLock = new Object();
    private CompletableFuture<List<String>> versionQuery;

    public RuntimeTaskService(RuntimeProvider runtimes, InstanceStore store, JobManager jobs, DataRoot data,
                              AuditLog audit, RuntimeCompatibility compatibility, LogFiles logs) {
        this.runtimes = runtimes;
        this.store = store;
        this.jobs = jobs;
        this.data = data;
        this.audit = audit;
        this.compatibility = compatibility;
        this.logs = logs;
    }

    /**
     * 可用版本查询（发现类任务，不占用全局变更锁）：60 秒上游超时；
     * 同一时刻在途查询合并为一个请求（方案 §4.3“合并重复请求”）。
     */
    public List<String> availableVersions() {
        CompletableFuture<List<String>> query;
        synchronized (versionQueryLock) {
            if (versionQuery != null && !versionQuery.isDone()) {
                query = versionQuery;
            } else {
                CompletableFuture<List<String>> created = new CompletableFuture<>();
                versionQuery = created;
                Thread.ofVirtual().name("pcl-version-query").start(() -> runVersionQuery(created));
                query = created;
            }
        }
        try {
            return query.get(65, TimeUnit.SECONDS);
        } catch (TimeoutException failure) {
            throw ApiException.upstream("UPSTREAM_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT,
                    "版本查询超时，请检查下载源或代理后重试。");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw ApiException.upstream("UPSTREAM_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT, "版本查询被中断，请重试。");
        } catch (ExecutionException failure) {
            throw upstreamFailure(failure.getCause());
        }
    }

    private void runVersionQuery(CompletableFuture<List<String>> target) {
        try {
            List<String> versions = List.copyOf(runtimes.get().availableVersions());
            logs.append("已读取 " + versions.size() + " 个 Harness 版本；列表不会自动升级已安装实例。");
            target.complete(versions);
        } catch (Throwable failure) {
            target.completeExceptionally(failure);
        } finally {
            synchronized (versionQueryLock) {
                if (versionQuery == target) {
                    versionQuery = null;
                }
            }
        }
    }

    /** POST /api/runtimes：安装（202 Job，20 分钟），安装成功后尝试能力探测（失败只记日志）。 */
    public JobManager.View install(String version, String ip) {
        String value = requireVersion(version);
        audit.record("RUNTIME_INSTALL_REQUESTED", ip, "version=" + value);
        logs.append("请求安装运行时：Harness " + value);
        return jobs.submit("INSTALL_RUNTIME", Duration.ofMinutes(20), context -> {
            RuntimeService runtime = runtimes.get();
            context.phase("准备安装");
            context.log("开始安装 Harness " + value + "（首次安装需要下载依赖，可能需要数分钟）。");
            runtime.install(value, context::log);
            context.checkCancelled();

            String summary = "待检测";
            context.phase("检测运行时能力");
            try {
                Capabilities capabilities = runtime.inspect(value, context::log);
                summary = capabilities.summary();
                compatibility.put(value, summary);
                context.log("运行时能力检测完成。");
            } catch (IOException | RuntimeException probeFailure) {
                // 安装已成功；探测失败不使安装任务失败（探测可单独重试）。
                context.log("运行时能力检测未完成（不影响安装）："
                        + LogSanitizer.oneLine(probeFailure.getMessage(), 200));
            }
            context.checkCancelled();
            return new ApiDtos.RuntimeInstallResultDto(value, true, summary);
        });
    }

    /** POST /api/runtimes/{version}/probe：能力探测（202 Job，90 秒）。 */
    public JobManager.View probe(String version, String ip) {
        String value = requireVersion(version);
        assertInstalled(value);
        audit.record("RUNTIME_PROBE_REQUESTED", ip, "version=" + value);
        return jobs.submit("PROBE_RUNTIME", Duration.ofSeconds(90), context -> {
            RuntimeService runtime = runtimes.get();
            context.phase("检测运行时能力");
            Capabilities capabilities = runtime.inspect(value, context::log);
            String summary = capabilities.summary();
            compatibility.put(value, summary);
            context.checkCancelled();
            return new ApiDtos.RuntimeProbeResultDto(value, summary);
        });
    }

    /** DELETE /api/runtimes/{version}：被实例引用时 409，否则目录移入 trash。 */
    public String delete(String version, String ip) {
        String value = requireVersion(version);
        jobs.ensureIdle();
        if (store.list().stream().anyMatch(row -> row.runtimeVersion().equals(value))) {
            throw new ApiException("CONFLICT_IN_USE", HttpStatus.CONFLICT,
                    "有实例引用此运行时，请先调整或删除相关实例。");
        }
        RuntimeService runtime;
        try {
            runtime = runtimes.get();
        } catch (RuntimeException failure) {
            throw upstreamFailure(failure);
        }
        Path directory = runtime.runtimeDirectory(value);
        if (!Files.isDirectory(directory)) {
            throw ApiException.notFound("运行时未安装：" + value + "。");
        }
        Path trashed;
        try {
            trashed = Trash.move(data, directory, "runtime-" + value);
        } catch (IOException failure) {
            log.warn("运行时目录移入回收目录失败：{}", failure.toString());
            throw ApiException.internal("运行时目录移入回收目录失败，请检查数据目录权限。");
        }
        runtimes.invalidate();
        compatibility.remove(value);
        String trashedPath = trashed == null ? "" : trashed.toString();
        audit.record("RUNTIME_DELETED", ip, "version=" + value
                + " trashed=" + LogSanitizer.oneLine(trashedPath, 240));
        logs.append("运行时已移入回收目录：Harness " + value + (trashedPath.isEmpty() ? "" : " → " + trashedPath));
        return trashedPath;
    }

    /** 版本格式校验：与 core 的 RuntimeService 一致，要求完整 SemVer。 */
    private static String requireVersion(String version) {
        String value = version == null ? "" : version.trim();
        if (!SemVer.isVersion(value)) {
            throw ApiException.validation("请选择完整的 Harness 版本号（例如 0.2.0-rc.2）。");
        }
        return value;
    }

    private void assertInstalled(String version) {
        List<String> installed;
        try {
            installed = runtimes.get().installedVersions();
        } catch (IOException failure) {
            throw ApiException.internal("读取已安装运行时失败："
                    + LogSanitizer.oneLine(failure.getMessage(), 200));
        } catch (RuntimeException failure) {
            throw upstreamFailure(failure);
        }
        if (!installed.contains(version)) {
            throw ApiException.notFound("运行时未安装：" + version + "。请先安装后再探测。");
        }
    }

    private ApiException upstreamFailure(Throwable cause) {
        if (cause == null) {
            return ApiException.upstream("UPSTREAM_ERROR", HttpStatus.BAD_GATEWAY, DEFAULT_UPSTREAM_MESSAGE);
        }
        String message = cause.getMessage() == null ? "" : cause.getMessage();
        if (message.contains("超时")) {
            return ApiException.upstream("UPSTREAM_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT, message);
        }
        if (message.contains("版本查询") || message.contains("npm")) {
            return ApiException.upstream("UPSTREAM_ERROR", HttpStatus.BAD_GATEWAY, message);
        }
        if (cause instanceof IllegalStateException && !message.isBlank()) {
            return ApiException.upstream("UPSTREAM_ERROR", HttpStatus.BAD_GATEWAY, message);
        }
        log.warn("版本查询失败：{}", cause.toString());
        return ApiException.upstream("UPSTREAM_ERROR", HttpStatus.BAD_GATEWAY, DEFAULT_UPSTREAM_MESSAGE);
    }
}
