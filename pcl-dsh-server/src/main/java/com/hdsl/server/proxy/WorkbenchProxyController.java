package com.hdsl.server.proxy;

import com.hdsl.core.Instance;
import com.hdsl.core.InstanceStore;
import com.hdsl.core.ProcessService;
import com.hdsl.server.api.ApiException;
import com.hdsl.server.config.PclProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * DSH 工作台反向代理（方案 §4.6 / M0 结果）：/workbench/{instanceId}/** → 127.0.0.1:实例端口。
 *
 * <ul>
 *   <li>实例必须存在、进程运行且已就绪，否则 404/409；出站目标只允许 127.0.0.1 + 登记端口（SSRF 白名单）。</li>
 *   <li>token 引导：无 dsh-auth-* Cookie 且无 token 查询时 302 到 /workbench/{id}/?token=...（token 只在内存）。</li>
 *   <li>Cookie/Set-Cookie、Location 重写为 /workbench/{id} 前缀；去 Domain/Secure，避免回环 Cookie 失效。</li>
 *   <li>流式转发（SSE 逐块 flush），普通请求超时 60s → 504、连接失败 → 502。</li>
 *   <li>日志不记录查询串、token、Cookie 或请求头原文。</li>
 * </ul>
 *
 * <p>class 级 headers 条件把 WebSocket Upgrade 握手让给 Spring WebSocket 映射，普通 HTTP 仍由本控制器处理。</p>
 */
@RestController
@RequestMapping(value = "/workbench/{instanceId}", headers = "Upgrade!=websocket")
public class WorkbenchProxyController {

    private static final Logger log = LoggerFactory.getLogger(WorkbenchProxyController.class);
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");
    private static final String INSTANCE_NOT_READY = "INSTANCE_NOT_READY";

    private final InstanceStore store;
    private final ProcessService processes;
    private final PclProperties properties;
    private final HttpClient http;

    public WorkbenchProxyController(InstanceStore store, ProcessService processes, PclProperties properties) {
        this.store = store;
        this.processes = processes;
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.getProxyConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** HTTP/SSE 代理入口。方法限定为工作台会用到的安全集合。 */
    @RequestMapping(value = {"", "/", "/**"},
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH,
                    RequestMethod.DELETE, RequestMethod.HEAD})
    public void proxy(@PathVariable("instanceId") String instanceId,
                      HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!properties.isProxyEnabled()) {
            throw ApiException.notFound("工作台反向代理未启用。");
        }
        String rawUri = rawPath(request);
        String remainder = ProxyTargets.remainder(rawUri, instanceId);
        if (remainder == null) {
            throw ApiException.badRequest("工作台路径无效。");
        }
        String query = request.getQueryString();
        if (remainder.isEmpty()) {
            // 缺少结尾斜杠会让 HTML 的 base href="./" 解析到错误目录，统一补斜杠。
            response.setStatus(HttpServletResponse.SC_MOVED_TEMPORARILY);
            response.setHeader("Location", ProxyTargets.PREFIX + instanceId + "/"
                    + (query == null || query.isEmpty() ? "" : "?" + query));
            response.setHeader("Cache-Control", "no-store");
            return;
        }
        Instance instance = requireInstance(instanceId);
        if (!processes.running(instanceId)) {
            throw ApiException.conflict(INSTANCE_NOT_READY, "请先启动当前实例。");
        }
        if (!"运行中".equals(processes.status(instance))) {
            throw ApiException.conflict(INSTANCE_NOT_READY, "网页服务尚未确认就绪，请等待启动完成或检查任务日志。");
        }
        URI bootstrap = processes.webAddress(instance);
        // token 引导：仅当没有 dsh-auth-* Cookie 且请求未携带 token 时 302 换取 Cookie。
        if (!hasDshAuthCookie(request) && !ProxyTargets.hasToken(query)) {
            response.setStatus(HttpServletResponse.SC_FOUND);
            response.setHeader("Location", ProxyTargets.bootstrapLocation(instanceId, instance, bootstrap));
            response.setHeader("Cache-Control", "no-store");
            return;
        }
        URI target;
        try {
            target = ProxyTargets.upstreamUri(instance, bootstrap, remainder, query);
        } catch (IllegalArgumentException failure) {
            throw new ApiException("UPSTREAM_TARGET_REJECTED", HttpStatus.BAD_GATEWAY, "反代目标不在白名单内。");
        }
        forward(instance, target, request, response);
    }

    // ------------------------------------------------------------------ 转发

    private void forward(Instance instance, URI target, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String method = request.getMethod();
        HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(properties.getProxyTimeout());
        if (BODY_METHODS.contains(method)) {
            builder.method(method, HttpRequest.BodyPublishers.ofInputStream(() -> {
                try {
                    return request.getInputStream();
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        copyRequestHeaders(request, builder, instance);

        HttpResponse<InputStream> upstream;
        try {
            upstream = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException timeout) {
            throw new ApiException("UPSTREAM_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT,
                    "工作台响应超时，请稍后重试。");
        } catch (IOException failure) {
            // 只记录异常类型与实例 id；不记录请求头/查询串（可能含 token）。
            log.warn("工作台反代连接失败：实例 {}（{}）", instance.id(), failure.getClass().getSimpleName());
            throw new ApiException("UPSTREAM_ERROR", HttpStatus.BAD_GATEWAY,
                    "无法连接实例工作台，请确认实例仍在运行。");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ApiException("UPSTREAM_ERROR", HttpStatus.BAD_GATEWAY, "工作台请求已中断。");
        }

        try (InputStream body = upstream.body()) {
            response.setStatus(upstream.statusCode());
            copyResponseHeaders(upstream, response, instance);
            if ("HEAD".equals(method)) {
                response.flushBuffer();
                return;
            }
            String contentType = upstream.headers().firstValue("content-type")
                    .orElse("").toLowerCase(Locale.ROOT);
            boolean sse = contentType.contains("text/event-stream");
            OutputStream output = response.getOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = body.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                if (sse) {
                    // SSE/长连接必须立即 flush，否则前端事件会滞留。
                    response.flushBuffer();
                }
            }
            output.flush();
        } catch (IOException copyFailure) {
            // 客户端断开或上游提前关闭：状态码已发出，只能关闭连接；日志不含敏感内容。
            log.debug("工作台反代流中断：实例 {}（{}）", instance.id(), copyFailure.getClass().getSimpleName());
        }
    }

    private static void copyRequestHeaders(HttpServletRequest request, HttpRequest.Builder builder, Instance instance) {
        String cookie = dshCookieHeader(request);
        for (String name : Collections.list(request.getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (ProxyTargets.hopByHop(lower)
                    || "host".equals(lower) || "content-length".equals(lower)
                    || "cookie".equals(lower) || "origin".equals(lower) || "referer".equals(lower)
                    || "x-xsrf-token".equals(lower) || "x-csrf-token".equals(lower)
                    || "forwarded".equals(lower) || lower.startsWith("x-forwarded-")) {
                continue;
            }
            for (String value : Collections.list(request.getHeaders(name))) {
                builder.header(name, value);
            }
        }
        if (cookie != null && !cookie.isEmpty()) {
            builder.header("Cookie", cookie);
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            // 上游只接受回环 Origin；浏览器来源改写为实例地址。
            builder.header("Origin", "http://127.0.0.1:" + instance.port());
        }
    }

    private static void copyResponseHeaders(HttpResponse<InputStream> upstream, HttpServletResponse response,
                                            Instance instance) {
        upstream.headers().map().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (ProxyTargets.hopByHop(lower)) {
                return;
            }
            if ("set-cookie".equals(lower)) {
                for (String value : values) {
                    response.addHeader("Set-Cookie", ProxyTargets.rewriteSetCookie(value, instance.id()));
                }
                return;
            }
            if ("location".equals(lower)) {
                for (String value : values) {
                    response.addHeader("Location", ProxyTargets.rewriteLocation(value, instance.id(), instance.port()));
                }
                return;
            }
            if ("cache-control".equals(lower) || "expires".equals(lower) || "pragma".equals(lower)) {
                return;
            }
            for (String value : values) {
                response.addHeader(name, value);
            }
        });
        response.setHeader("Cache-Control", "no-store");
    }

    // ------------------------------------------------------------------ 工具

    private Instance requireInstance(String instanceId) {
        try {
            InstanceStore.validateId(instanceId);
        } catch (IOException failure) {
            throw ApiException.validation(failure.getMessage());
        }
        try {
            return store.find(instanceId);
        } catch (IOException failure) {
            throw ApiException.notFound("实例不存在：" + instanceId);
        }
    }

    private static boolean hasDshAuthCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (cookie.getName() != null && cookie.getName().startsWith("dsh-auth-")) {
                return true;
            }
        }
        return false;
    }

    /** 只转发 DSH 自己的 Cookie；本应用会话（JSESSIONID）与 CSRF Token 不进入实例进程。 */
    private static String dshCookieHeader(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        StringBuilder header = new StringBuilder();
        for (Cookie cookie : cookies) {
            String name = cookie.getName();
            if (name == null || "JSESSIONID".equalsIgnoreCase(name) || "XSRF-TOKEN".equalsIgnoreCase(name)) {
                continue;
            }
            if (header.length() > 0) {
                header.append("; ");
            }
            header.append(name).append('=').append(cookie.getValue() == null ? "" : cookie.getValue());
        }
        return header.toString();
    }

    private static String rawPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path;
    }
}
