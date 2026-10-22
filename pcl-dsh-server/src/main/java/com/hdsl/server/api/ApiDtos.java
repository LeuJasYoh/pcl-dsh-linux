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
}
