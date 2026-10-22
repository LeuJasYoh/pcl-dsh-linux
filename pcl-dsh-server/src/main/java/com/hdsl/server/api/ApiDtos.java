package com.hdsl.server.api;

import java.util.List;

/** REST DTO 集合（record 只读快照；不含密码、密钥、token 或环境变量）。 */
public final class ApiDtos {

    private ApiDtos() {
    }

    public record HealthDto(String status, String version, long uptimeSeconds) {
    }

    public record MeDto(boolean authenticated, String csrfToken, String version, boolean initialPasswordPending) {
    }

    public record LoginRequest(String password) {
    }

    public record PasswordChangeRequest(String oldPassword, String newPassword) {
    }

    public record SettingsRequest(String proxy, String registry, String background) {
    }

    public record SettingsDto(String proxy, String registry, String background) {
        public SettingsDto {
            proxy = proxy == null ? "" : proxy;
            registry = registry == null ? "" : registry;
            background = background == null ? "" : background;
        }
    }

    public record StatusDto(String code, String message) {
    }

    public record InstanceDto(String id, String name, String version, String profile, int port, String status,
                              String path, List<String> profiles, String accountId) {
        public InstanceDto {
            profiles = profiles == null ? List.of() : List.copyOf(profiles);
            accountId = accountId == null ? "" : accountId;
        }
    }

    public record RuntimeDto(String version, boolean installed, String compatibility) {
    }

    public record AccountDto(String id, String name, String provider, String baseUrl, String model,
                             boolean hasKey, String status) {
    }

    /**
     * GET /api/state 聚合快照（方案 §4.9）。
     * accounts 允许为 null：账户清单读取失败时前端可显示“账户不可用”，不伪造空列表。
     */
    public record StateDto(String version, String currentInstanceId, List<InstanceDto> instances,
                           List<RuntimeDto> runtimes, List<AccountDto> accounts, StatusDto status,
                           SettingsDto settings, List<String> availableVersions) {
        public StateDto {
            instances = instances == null ? List.of() : List.copyOf(instances);
            runtimes = runtimes == null ? List.of() : List.copyOf(runtimes);
            status = status == null ? new StatusDto("READY", "就绪") : status;
            settings = settings == null ? new SettingsDto("", "https://registry.npmjs.org", "") : settings;
            availableVersions = availableVersions == null ? List.of() : List.copyOf(availableVersions);
        }
    }

    // ---------------------------------------------------------------- 实例写接口（M1 第二增量）

    /** POST /api/instances。port 为空 / "0" 表示自动分配；accountId 可为空。 */
    public record CreateInstanceRequest(String name, String version, String profile, String port, String accountId) {
    }

    /**
     * PUT /api/instances/{id}。本增量只应用 name / port / accountId；
     * version / profile 仅允许省略或与现值相同，变更时返回 409（下个增量提供）。
     */
    public record UpdateInstanceRequest(String name, String version, String profile, String port, String accountId) {
    }

    /** PUT /api/instances/{id}/account；空串表示解除绑定。 */
    public record AccountBindingRequest(String accountId) {
    }

    /** DELETE 类接口返回：被移入回收目录的绝对路径，空串表示原目录不存在。 */
    public record TrashedDto(String trashedPath) {
        public TrashedDto {
            trashedPath = trashedPath == null ? "" : trashedPath;
        }
    }

    /** GET /api/instances/{id}/web-url。 */
    public record WebUrlDto(String url) {
    }

    /** LAUNCH_INSTANCE 任务成功结果。 */
    public record LaunchResultDto(String instanceId, String webUrl) {
    }

    // ---------------------------------------------------------------- 运行时任务（M1 第二增量）

    /** GET /api/runtimes/available。 */
    public record AvailableVersionsDto(List<String> versions) {
        public AvailableVersionsDto {
            versions = versions == null ? List.of() : List.copyOf(versions);
        }
    }

    /** POST /api/runtimes。 */
    public record RuntimeInstallRequest(String version) {
    }

    /** INSTALL_RUNTIME 任务成功结果。 */
    public record RuntimeInstallResultDto(String version, boolean installed, String compatibility) {
    }

    /** PROBE_RUNTIME 任务成功结果。 */
    public record RuntimeProbeResultDto(String version, String compatibility) {
    }

    // ---------------------------------------------------------------- 日志（M1 第二增量）

    /** GET /api/logs/tail：已脱敏的单行文本（行内自带时间前缀）。 */
    public record LogTailDto(List<String> lines) {
        public LogTailDto {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    // ---------------------------------------------------------------- 账户（M1 第三增量）

    /** GET /api/accounts/providers：账户提供商预设（不含密钥）。 */
    public record ProviderDto(String id, String name, String baseUrl, String model) {
        public ProviderDto {
            baseUrl = baseUrl == null ? "" : baseUrl;
            model = model == null ? "" : model;
        }
    }

    /** POST/PUT /api/accounts；apiKey 为空表示编辑时保留原密钥（新建时必须提供）。 */
    public record AccountSaveRequest(String name, String provider, String baseUrl, String model, String apiKey) {
    }

    public record AccountDeletedDto(String id) {
    }

    // ---------------------------------------------------------------- 插件（M1 第三增量）

    /** GET /api/instances/{id}/plugins（字段与桌面版 UiState.PluginItem 一致）。 */
    public record PluginDto(String name, String version, String description, String source) {
        public PluginDto {
            name = name == null ? "" : name;
            version = version == null ? "" : version;
            description = description == null ? "" : description;
            source = source == null ? "" : source;
        }
    }

    /** POST /api/instances/{id}/plugins：npm 包名/坐标。 */
    public record PluginMutationRequest(String spec) {
    }

    // ---------------------------------------------------------------- 插件目录（M1 第三增量）

    public record PluginCatalogEntryDto(String repo, String name, String description, int stars,
                                        String url, String spec, String status) {
        public PluginCatalogEntryDto {
            repo = repo == null ? "" : repo;
            name = name == null ? "" : name;
            description = description == null ? "" : description;
            url = url == null ? "" : url;
            spec = spec == null ? "" : spec;
            status = status == null ? "" : status;
        }
    }

    /** GET /api/plugin-catalog（字段与桌面版 UiState.PluginCatalog 对齐）。 */
    public record PluginCatalogDto(List<PluginCatalogEntryDto> entries, long total, int page,
                                   boolean hasNext, boolean fromCache, String warning) {
        public PluginCatalogDto {
            entries = entries == null ? List.of() : List.copyOf(entries);
            warning = warning == null ? "" : warning;
        }
    }

    public record InstallTargetDto(String name, String version, String spec, String packagePath,
                                   String evidenceUrl) {
        public InstallTargetDto {
            name = name == null ? "" : name;
            version = version == null ? "" : version;
            spec = spec == null ? "" : spec;
            packagePath = packagePath == null ? "" : packagePath;
            evidenceUrl = evidenceUrl == null ? "" : evidenceUrl;
        }
    }

    /** GET /api/plugin-catalog/{owner}/{repo}。 */
    public record PluginDetailsDto(String repo, String status, String message, boolean fromCache,
                                   List<InstallTargetDto> targets) {
        public PluginDetailsDto {
            repo = repo == null ? "" : repo;
            status = status == null ? "" : status;
            message = message == null ? "" : message;
            targets = targets == null ? List.of() : List.copyOf(targets);
        }
    }

    // ---------------------------------------------------------------- 背景资源（M1 第三增量）

    /** POST /api/settings/background：返回可直接写入 settings.background 的资源地址。 */
    public record BackgroundDto(String background) {
        public BackgroundDto {
            background = background == null ? "" : background;
        }
    }
}
