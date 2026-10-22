package com.hdsl.server.proxy;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册 /workbench/** 的 WebSocket 转发端点（方案 §4.6）。
 *
 * <p>同源校验、实例存在/运行/就绪校验在 {@link WorkbenchHandshakeInterceptor} 中完成；
 * Spring 的 WebSocket 映射 order 晚于 MVC，HTTP 请求由 {@link WorkbenchProxyController} 处理，
 * 只有 Upgrade 握手（控制器 class 级 headers 条件排除）会进入本端点。</p>
 *
 * <p>允许来源用 " * " 交给框架放宽，实际安全边界是我们自己的 HandshakeInterceptor 同源校验
 * 与 Spring Security 会话鉴权（跨站握手拿不到 SameSite=Lax 的会话 Cookie）。</p>
 */
@Configuration
@EnableWebSocket
public class WorkbenchWebSocketConfig implements WebSocketConfigurer {

    private final WorkbenchWebSocketHandler handler;
    private final WorkbenchHandshakeInterceptor interceptor;

    public WorkbenchWebSocketConfig(WorkbenchWebSocketHandler handler, WorkbenchHandshakeInterceptor interceptor) {
        this.handler = handler;
        this.interceptor = interceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/workbench/**")
                .addInterceptors(interceptor)
                .setAllowedOriginPatterns("*");
    }
}
