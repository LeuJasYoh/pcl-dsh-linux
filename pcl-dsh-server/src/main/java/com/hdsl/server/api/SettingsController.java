package com.hdsl.server.api;

import com.hdsl.core.InstanceStore;
import com.hdsl.server.config.RuntimeProvider;
import com.hdsl.server.job.JobManager;
import com.hdsl.server.security.AuditLog;
import com.hdsl.server.security.ClientIp;
import com.hdsl.server.state.StateAssembler;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Set;

/** 设置读写：复用 InstanceStore.settings（launcher-v2.json），变更后重建 RuntimeService。 */
@RestController
@RequestMapping("/api")
public class SettingsController {

    private static final String DEFAULT_REGISTRY = "https://registry.npmjs.org";

    private final InstanceStore store;
    private final StateAssembler state;
    private final RuntimeProvider runtimes;
    private final JobManager jobs;
    private final AuditLog audit;

    public SettingsController(InstanceStore store, StateAssembler state, RuntimeProvider runtimes,
                              JobManager jobs, AuditLog audit) {
        this.store = store;
        this.state = state;
        this.runtimes = runtimes;
        this.jobs = jobs;
        this.audit = audit;
    }

    @GetMapping("/settings")
    public ApiDtos.SettingsDto settings() {
        return state.settings();
    }

    @PutMapping("/settings")
    public ApiDtos.SettingsDto update(@RequestBody(required = false) ApiDtos.SettingsRequest body,
                                      HttpServletRequest request) {
        jobs.ensureIdle();
        String proxy = body == null || body.proxy() == null ? "" : body.proxy().trim();
        String registry = body == null || body.registry() == null || body.registry().isBlank()
                ? DEFAULT_REGISTRY : body.registry().trim();
        String background = body == null || body.background() == null ? "" : body.background().trim();
        validate(registry, proxy);
        String previousRegistry = store.setting("registry", DEFAULT_REGISTRY);
        String previousProxy = store.setting("proxy", "");
        try {
            store.settings(Map.of("proxy", proxy, "registry", registry, "background", background));
        } catch (IOException e) {
            throw ApiException.internal("设置保存失败，请检查数据目录权限。");
        }
        if (!registry.equals(previousRegistry) || !proxy.equals(previousProxy == null ? "" : previousProxy)) {
            runtimes.invalidate();
        }
        audit.record("SETTINGS_CHANGED", ClientIp.of(request),
                "registry=" + registry + (proxy.isBlank() ? "" : " proxy=已设置"));
        return state.settings();
    }

    /** 与桌面版 UiActions 的校验文案保持一致。 */
    private static void validate(String registry, String proxy) {
        try {
            URI source = URI.create(registry);
            String scheme = source.getScheme();
            if (scheme == null || !Set.of("https", "http").contains(scheme)
                    || source.getHost() == null || source.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
            if (!proxy.isBlank()) {
                URI proxyUri = URI.create(proxy);
                if (!"http".equals(proxyUri.getScheme()) || proxyUri.getHost() == null
                        || proxyUri.getPort() < 1 || proxyUri.getPort() > 65535
                        || proxyUri.getUserInfo() != null) {
                    throw new IllegalArgumentException();
                }
            }
        } catch (IllegalArgumentException e) {
            throw ApiException.validation(
                    "Registry 须为 HTTP(S) 地址；代理请填写如 http://127.0.0.1:7890，且不包含凭据。");
        }
    }
}
