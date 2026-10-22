package com.hdsl.server.proxy;

import com.hdsl.core.Instance;
import com.hdsl.core.InstanceStore;
import com.hdsl.core.ProcessService;
import com.hdsl.server.config.PclProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;

/**
 * 工作台 WebSocket 握手校验（方案 §4.6）：登录态由 Spring Security 过滤器链保证；
 * 这里校验实例存在/运行/就绪、同源、并把仅用于回环的上游 ws:// 地址放入会话属性。
 */
@Component
public class WorkbenchHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_UPSTREAM = "pcl.workbench.ws.upstream";
    public static final String ATTR_COOKIE = "pcl.workbench.ws.cookie";
    public static final String ATTR_SUBPROTOCOL = "pcl.workbench.ws.subprotocol";

    private static final Logger log = LoggerFactory.getLogger(WorkbenchHandshakeInterceptor.class);

    private final InstanceStore store;
    private final ProcessService processes;
    private final PclProperties properties;

    public WorkbenchHandshakeInterceptor(InstanceStore store, ProcessService processes, PclProperties properties) {
        this.store = store;
        this.processes = processes;
        this.properties = properties;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!properties.isProxyEnabled()) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }
        String path = request.getURI().getRawPath();
        String instanceId = ProxyTargets.instanceId(path);
        if (instanceId == null) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        Instance instance;
        try {
            InstanceStore.validateId(instanceId);
            instance = store.find(instanceId);
        } catch (IOException failure) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }
        if (!processes.running(instanceId) || !"运行中".equals(processes.status(instance))) {
            response.setStatusCode(HttpStatus.CONFLICT);
            return false;
        }
        String origin = request.getHeaders().getOrigin();
        if (origin != null && !origin.isBlank()
                && !sameOrigin(origin, request.getHeaders().getHost())) {
            log.warn("拒绝跨源工作台 WebSocket 握手：实例 {}", instanceId);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        String remainder = ProxyTargets.remainder(path, instanceId);
        if (remainder == null) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        try {
            URI httpTarget = ProxyTargets.upstreamUri(instance, processes.webAddress(instance), remainder,
                    request.getURI().getRawQuery());
            String query = httpTarget.getRawQuery();
            URI upstream = URI.create("ws://127.0.0.1:" + instance.port() + httpTarget.getRawPath()
                    + (query == null || query.isEmpty() ? "" : "?" + query));
            attributes.put(ATTR_UPSTREAM, upstream.toString());
        } catch (IllegalArgumentException failure) {
            response.setStatusCode(HttpStatus.BAD_GATEWAY);
            return false;
        }
        attributes.put(ATTR_COOKIE, request.getHeaders().getFirst(HttpHeaders.COOKIE));
        attributes.put(ATTR_SUBPROTOCOL, request.getHeaders().getFirst("Sec-WebSocket-Protocol"));
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需处理。
    }

    /** 浏览器来源必须与当前 Host 同源（Nginx 反代下 Origin/Host 都是公网地址）。 */
    private static boolean sameOrigin(String origin, InetSocketAddress host) {
        if (host == null) {
            return false;
        }
        try {
            URI uri = URI.create(origin);
            if (uri.getHost() == null) {
                return false;
            }
            int originPort = uri.getPort() >= 0 ? uri.getPort()
                    : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
            return uri.getHost().equalsIgnoreCase(host.getHostString()) && originPort == host.getPort();
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }
}
