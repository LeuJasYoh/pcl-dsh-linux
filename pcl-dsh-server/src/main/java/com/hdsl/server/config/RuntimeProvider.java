package com.hdsl.server.config;

import com.hdsl.core.InstanceStore;
import com.hdsl.runtime.RuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * RuntimeService 依赖 settings 中的 registry / proxy，构造开销与网络客户端都很轻，
 * 但设置变更后必须重建；因此这里做懒加载与自动失效，避免在设置保存路径上重复实现业务。
 *
 * <p>M1 同时接入 core 新增的“额外只读工具根 + 可写 shim 目录”参数：
 * 发布包 tools 目录优先于 data/tools，shim 写入 data/tools/shims。</p>
 */
@Component
public class RuntimeProvider {

    private static final Logger log = LoggerFactory.getLogger(RuntimeProvider.class);
    private static final String DEFAULT_REGISTRY = "https://registry.npmjs.org";

    private final DataRoot data;
    private final InstanceStore store;
    private final PclProperties properties;

    private RuntimeService service;
    private String registry;
    private String proxy;
    private volatile Path extraToolRoot;

    public RuntimeProvider(DataRoot data, InstanceStore store, PclProperties properties) {
        this.data = data;
        this.store = store;
        this.properties = properties;
    }

    /** 取当前 settings 对应的 RuntimeService；设置变化时自动重建。 */
    public synchronized RuntimeService get() {
        String configuredRegistry = store.setting("registry", DEFAULT_REGISTRY);
        if (configuredRegistry == null || configuredRegistry.isBlank()) {
            configuredRegistry = DEFAULT_REGISTRY;
        }
        configuredRegistry = configuredRegistry.trim();
        String configuredProxy = store.setting("proxy", "");
        configuredProxy = configuredProxy == null ? "" : configuredProxy.trim();
        if (service == null || !configuredRegistry.equals(registry) || !configuredProxy.equals(proxy)) {
            try {
                service = new RuntimeService(data.root(), configuredRegistry, configuredProxy,
                        toolRoot(), data.tools().resolve("shims"));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("运行时的 registry / 代理设置无效（请检查“设置”页面）："
                        + e.getMessage(), e);
            }
            registry = configuredRegistry;
            proxy = configuredProxy;
        }
        return service;
    }

    /** 设置变更后调用：下次 get() 使用新的 registry / proxy。 */
    public synchronized void invalidate() {
        service = null;
        registry = null;
        proxy = null;
    }

    /**
     * 只读工具根：显式配置 pcl.tool-root 优先；否则从当前 jar/classes 所在目录推断
     * （发布包布局 releases/0.3.0/pcl-dsh-linux.jar + tools/，故取父目录下的 tools）。
     */
    private Path toolRoot() {
        Path cached = extraToolRoot;
        if (cached != null) {
            return Files.isDirectory(cached) ? cached : null;
        }
        String configured = properties.getToolRoot();
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured.trim()).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                extraToolRoot = path;
                return path;
            }
            log.warn("pcl.tool-root 指向的目录不存在或不可读：{}", path);
            return null;
        }
        try {
            var source = RuntimeProvider.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                Path location = Path.of(source.getLocation().toURI());
                Path base = Files.isDirectory(location) ? location : location.getParent();
                if (base != null) {
                    Path tools = base.resolve("tools");
                    if (Files.isDirectory(tools)) {
                        extraToolRoot = tools;
                        return tools;
                    }
                }
            }
        } catch (URISyntaxException | RuntimeException e) {
            log.debug("无法推断发布包 tools 目录：{}", e.toString());
        }
        return null;
    }
}
