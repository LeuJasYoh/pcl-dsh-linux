package com.hdsl.server.api;

import com.hdsl.core.InstanceStore;
import com.hdsl.server.asset.BackgroundService;
import com.hdsl.server.config.CatalogProvider;
import com.hdsl.server.config.RuntimeProvider;
import com.hdsl.server.job.JobManager;
import com.hdsl.server.security.AuditLog;
import com.hdsl.server.security.ClientIp;
import com.hdsl.server.state.StateAssembler;
import com.hdsl.server.util.LogSanitizer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Set;

/**
 * 设置读写：复用 InstanceStore.settings（launcher-v2.json）。
 *
 * <p>registry/proxy 变更后重建 RuntimeProvider 与 CatalogProvider；
 * background 仅允许空、内置背景（builtin:whale-01…05）或本机上传资源地址。</p>
 */
@RestController
@RequestMapping("/api")
public class SettingsController {

    private static final String DEFAULT_REGISTRY = "https://registry.npmjs.org";

    private final InstanceStore store;
    private final StateAssembler state;
    private final RuntimeProvider runtimes;
    private final CatalogProvider catalogs;
    private final BackgroundService backgrounds;
    private final JobManager jobs;
    private final AuditLog audit;

    public SettingsController(InstanceStore store, StateAssembler state, RuntimeProvider runtimes,
                              CatalogProvider catalogs, BackgroundService backgrounds,
                              JobManager jobs, AuditLog audit) {
        this.store = store;
        this.state = state;
        this.runtimes = runtimes;
        this.catalogs = catalogs;
        this.backgrounds = backgrounds;
        this.jobs = jobs;
        this.audit = audit;
    }

    @GetMapping("/settings")
    public ApiDtos.SettingsDto settings() {
        return state.settings();
    }

    /** POST /api/settings/background：上传自定义背景（≤20 MB、PNG/JPEG），返回资源地址。 */
    @PostMapping("/settings/background")
    public ApiDtos.BackgroundDto uploadBackground(
            @RequestParam(value = "file", required = false) MultipartFile file, HttpServletRequest request) {
        return backgrounds.upload(file, ClientIp.of(request));
    }

    @PutMapping("/settings")
    public ApiDtos.SettingsDto update(@RequestBody(required = false) ApiDtos.SettingsRequest body,
                                      HttpServletRequest request) {
        jobs.ensureIdle();
        String proxy = body == null || body.proxy() == null ? "" : body.proxy().trim();
        String registry = body == null || body.registry() == null || body.registry().isBlank()
                ? DEFAULT_REGISTRY : body.registry().trim();
        String background = backgrounds.validateReference(body == null ? "" : body.background());
        validate(registry, proxy);
        String previousRegistry = store.setting("registry", DEFAULT_REGISTRY);
        String previousProxy = store.setting("proxy", "");
        try {
            store.settings(Map.of("proxy", proxy, "registry", registry, "background", background));
        } catch (IOException e) {
            throw ApiException.internal("设置保存失败，请检查数据目录权限。");
        }
        boolean sourceChanged = !registry.equals(previousRegistry) || !proxy.equals(previousProxy == null ? "" : previousProxy);
        if (sourceChanged) {
            runtimes.invalidate();
        }
        if (!proxy.equals(previousProxy == null ? "" : previousProxy)) {
            catalogs.invalidate();
        }
        audit.record("SETTINGS_CHANGED", ClientIp.of(request),
                "registry=" + registry
                        + (proxy.isBlank() ? "" : " proxy=已设置")
                        + " background=" + (background.isBlank() ? "默认" : LogSanitizer.oneLine(background, 160)));
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
