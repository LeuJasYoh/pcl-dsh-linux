package com.hdsl.server.plugin;

import com.hdsl.core.Instance;
import com.hdsl.core.InstanceStore;
import com.hdsl.runtime.PluginEntry;
import com.hdsl.runtime.PluginService;
import com.hdsl.runtime.RuntimeService;
import com.hdsl.server.api.ApiDtos;
import com.hdsl.server.api.ApiException;
import com.hdsl.server.config.RuntimeProvider;
import com.hdsl.server.instance.InstanceService;
import com.hdsl.server.job.JobManager;
import com.hdsl.server.log.LogFiles;
import com.hdsl.server.security.AuditLog;
import com.hdsl.server.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 实例插件接口业务（方案 §4.2 / M3 预留）：列表读取与添加/更新/移除长任务。
 *
 * <p>复用 pcl-dsh-core 的 {@link PluginService}（原生 Harness plugin 命令 + pnpm），
 * 变更操作要求实例已停止（桌面版 ensureStopped），统一走 PLUGIN_MUTATE Job（20 分钟）。</p>
 */
@Component
public class PluginApiService {

    private static final Logger log = LoggerFactory.getLogger(PluginApiService.class);
    private static final Duration MUTATE_TIMEOUT = Duration.ofMinutes(20);

    private final InstanceService instances;
    private final InstanceStore store;
    private final RuntimeProvider runtimes;
    private final JobManager jobs;
    private final AuditLog audit;
    private final LogFiles logs;

    public PluginApiService(InstanceService instances, InstanceStore store, RuntimeProvider runtimes,
                            JobManager jobs, AuditLog audit, LogFiles logs) {
        this.instances = instances;
        this.store = store;
        this.runtimes = runtimes;
        this.jobs = jobs;
        this.audit = audit;
        this.logs = logs;
    }

    /** GET /api/instances/{id}/plugins?profile=：只读包元数据，不启动实例、不读取凭据。 */
    public List<ApiDtos.PluginDto> list(String instanceId, String profile) {
        Instance instance = instances.requireInstance(instanceId);
        String targetProfile = profile == null || profile.isBlank() ? instance.profile() : profile.trim();
        try {
            InstanceStore.validateProfile(targetProfile);
        } catch (IOException failure) {
            throw ApiException.validation(failure.getMessage());
        }
        RuntimeService runtime;
        try {
            runtime = runtimes.get();
        } catch (RuntimeException failure) {
            throw ApiException.internal("运行时设置无效，请检查“设置”页面：" + LogSanitizer.oneLine(failure.getMessage(), 200));
        }
        try {
            return new PluginService(runtime)
                    .list(instance.runtimeVersion(), targetProfile, store.home(instance), store.workspace(instance),
                            line -> logs.append(line))
                    .stream()
                    .map(PluginApiService::dto)
                    .toList();
        } catch (IOException failure) {
            log.warn("读取插件列表失败：{}", failure.toString());
            throw ApiException.internal("读取插件列表失败："
                    + LogSanitizer.oneLine(failure.getMessage(), 240));
        }
    }

    /**
     * 提交插件变更 Job（type=PLUGIN_MUTATE）。
     *
     * @param operation add / update / remove
     * @param spec      npm 包名、版本坐标或 file:/link: 本地路径
     */
    public JobManager.View mutate(String instanceId, String operation, String spec, String ip) {
        String action = normalize(operation);
        Instance instance = instances.requireInstance(instanceId);
        instances.ensureStoppedForMutation(instance);
        String value = spec == null ? "" : spec.trim();
        validateSpec(action, value);
        audit.record("PLUGIN_" + action.toUpperCase(Locale.ROOT) + "_REQUESTED", ip,
                "instance=" + instanceId + " spec=" + LogSanitizer.oneLine(value, 160));
        logs.append("请求插件" + label(action) + "：" + value + "（实例 " + instanceId + "）");
        String profile = instance.profile();
        return jobs.submit("PLUGIN_MUTATE", MUTATE_TIMEOUT, context -> {
            Instance current = instances.requireInstance(instanceId);
            instances.ensureStoppedForMutation(current);
            context.phase("插件" + label(action));
            new PluginService(runtimes.get()).mutate(action, value, current.runtimeVersion(), profile,
                    store.home(current), store.workspace(current), context::log);
            context.checkCancelled();
            context.log("插件操作完成；重启实例后载入新的组合包。");
            return Map.of("instanceId", instanceId, "operation", action, "profile", profile, "spec", value);
        });
    }

    private static ApiDtos.PluginDto dto(PluginEntry entry) {
        return new ApiDtos.PluginDto(entry.id(), entry.version(), entry.detail(),
                entry.official() ? "官方" : "社区 / 本地");
    }

    private static String normalize(String operation) {
        String value = operation == null ? "" : operation.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "add", "install" -> "add";
            case "update" -> "update";
            case "remove", "uninstall" -> "remove";
            default -> throw ApiException.validation("仅支持添加、更新和移除插件。");
        };
    }

    private static String label(String operation) {
        return switch (operation) {
            case "add" -> "安装";
            case "update" -> "更新";
            default -> "移除";
        };
    }

    /** 同步参数校验（与 core PluginService.mutate 的坐标规则一致，避免把明显非法的输入变成任务）。 */
    private static void validateSpec(String operation, String spec) {
        if (spec.isBlank()) {
            throw ApiException.validation("请输入插件包名或地址。");
        }
        if (spec.length() > 512) {
            throw ApiException.validation("插件包名或地址过长（最多 512 个字符）。");
        }
        if (spec.startsWith("-") || spec.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw ApiException.validation("插件坐标不能是命令参数或包含控制字符。");
        }
        if ("remove".equals(operation) && !spec.matches("(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*")) {
            throw ApiException.validation("移除操作需要完整 npm 包名。");
        }
    }
}
