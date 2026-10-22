package com.hdsl.server.config;

import com.hdsl.core.InstanceStore;
import com.hdsl.download.PluginCatalogService;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * PluginCatalogService 依赖 settings.proxy；代理变化后必须重建客户端，
 * 采用与 {@link RuntimeProvider} 相同的懒加载 + 失效策略。
 */
@Component
public class CatalogProvider {

    private final InstanceStore store;
    private PluginCatalogService service;
    private String proxy;

    public CatalogProvider(InstanceStore store) {
        this.store = store;
    }

    /** 取当前 settings.proxy 对应的目录客户端；代理变化时自动重建。 */
    public synchronized PluginCatalogService get() {
        String configured = store.setting("proxy", "");
        configured = configured == null ? "" : configured.trim();
        if (service == null || !configured.equals(proxy)) {
            try {
                service = new PluginCatalogService(configured.isBlank() ? null : URI.create(configured));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("插件目录代理设置无效（请检查“设置”页面）：" + e.getMessage(), e);
            }
            proxy = configured;
        }
        return service;
    }

    /** 设置变更后调用：下次 get() 使用新的代理。 */
    public synchronized void invalidate() {
        service = null;
        proxy = null;
    }
}
