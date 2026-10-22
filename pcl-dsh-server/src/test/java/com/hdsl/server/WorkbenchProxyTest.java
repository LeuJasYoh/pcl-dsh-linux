package com.hdsl.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hdsl.core.Instance;
import com.hdsl.core.InstanceStore;
import com.hdsl.core.ProcessService;
import com.hdsl.server.config.PclProperties;
import com.hdsl.server.security.ServerCredentials;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * /workbench 反代集成测试：真实内嵌 Tomcat + @com.sun.net.httpserver 模拟 DSH（不依赖 Node/真实 DSH）。
 *
 * <p>ProcessService 用 @MockitoBean 模拟“运行中/已就绪”状态与内存 bootstrap URL；
 * 覆盖 401、未运行 409、实例不存在 404、token 302 引导、Cookie/Location 重写、Host/Origin 改写、
 * POST body 透传（CSRF 豁免）、SSE 首事件不缓冲、超时 504、上游不可用 502、web-url 的 proxy 开关。</p>
 *
 * <p>WebSocket 转发依赖真实 DSH 页面行为，按任务说明留 M4 真实页面验证；本测试覆盖 HTTP/SSE。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkbenchProxyTest {

    private static final Path DATA_ROOT = TestDataRoots.prepare("workbench-proxy");
    private static final String PASSWORD = "WorkbenchProxyPass!2026";
    private static final String DSH_COOKIE = "dsh-auth-abcdef12=cookie-value";

    private static HttpServer dsh;
    private static ExecutorService dshExecutor;
    private static int dshPort;
    private static String instanceId;
    private static String sessionCookie;
    private static HttpClient client;

    private static final AtomicReference<String> CAPTURED_HOST = new AtomicReference<>();
    private static final AtomicReference<String> CAPTURED_ORIGIN = new AtomicReference<>();
    private static final AtomicReference<String> CAPTURED_COOKIE = new AtomicReference<>();

    @DynamicPropertySource
    static void dataRoot(DynamicPropertyRegistry registry) {
        registry.add("pcl.data-root", DATA_ROOT::toString);
    }

    @LocalServerPort
    private int appPort;

    @Autowired
    private ObjectMapper json;
    @Autowired
    private InstanceStore store;
    @Autowired
    private ServerCredentials credentials;
    @Autowired
    private PclProperties properties;
    @MockitoBean
    private ProcessService processes;

    @BeforeAll
    void startMockDshAndLogin() throws Exception {
        runMockDsh();
        Instance instance = new Instance(InstanceStore.newId(), "proxy-test", "0.1.0-rc.6", "web", dshPort, "", "");
        store.add(instance);
        instanceId = instance.id();

        String initial = Files.readString(DATA_ROOT.resolve("initial-password")).strip();
        credentials.changePassword(initial, PASSWORD);

        CookieManager cookieManager = new CookieManager();
        HttpClient loginClient = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest login = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"" + PASSWORD + "\"}"))
                .build();
        HttpResponse<String> response = loginClient.send(login, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, response.statusCode(), "代理测试需要先登录");
        for (HttpCookie cookie : cookieManager.getCookieStore().getCookies()) {
            if ("JSESSIONID".equals(cookie.getName())) {
                sessionCookie = "JSESSIONID=" + cookie.getValue();
            }
        }
        assertNotNull(sessionCookie, "登录必须建立会话");
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @AfterAll
    void stopMockDsh() {
        if (dsh != null) {
            dsh.stop(0);
        }
        if (dshExecutor != null) {
            dshExecutor.shutdownNow();
        }
    }

    @BeforeEach
    void stubProcessService() {
        when(processes.running(instanceId)).thenReturn(true);
        when(processes.status(any(Instance.class))).thenReturn("运行中");
        when(processes.webAddress(any(Instance.class)))
                .thenReturn(URI.create("http://127.0.0.1:" + dshPort + "/?token=m0-token"));
    }

    @Test
    @Order(1)
    void unauthenticatedRequestReturns401() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/workbench/" + instanceId + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("UNAUTHORIZED"));
    }

    @Test
    @Order(2)
    void unknownInstanceReturns404AndUnreadyReturns409() throws Exception {
        HttpResponse<String> missing = client.send(
                request("/workbench/i-000000000000/", true).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, missing.statusCode());

        when(processes.running(instanceId)).thenReturn(false);
        HttpResponse<String> unready = client.send(
                request("/workbench/" + instanceId + "/", true).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(409, unready.statusCode());
        assertEquals("INSTANCE_NOT_READY", json.readTree(unready.body()).path("code").asText());
    }

    @Test
    @Order(3)
    void redirectsToBootstrapTokenWhenNoDshCookie() throws Exception {
        HttpResponse<String> response = client.send(
                request("/workbench/" + instanceId + "/", false).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(302, response.statusCode());
        assertEquals("/workbench/" + instanceId + "/?token=m0-token",
                response.headers().firstValue("Location").orElse(""));
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    }

    @Test
    @Order(4)
    void tokenExchangeRewritesCookieAndRelativeLocation() throws Exception {
        HttpResponse<String> response = client.send(
                request("/workbench/" + instanceId + "/?token=m0-token", false).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(303, response.statusCode());
        assertEquals("./", response.headers().firstValue("Location").orElse(""));
        List<String> cookies = response.headers().allValues("Set-Cookie");
        assertFalse(cookies.isEmpty(), "上游 Set-Cookie 必须透传");
        String rewritten = String.join("\n", cookies);
        assertTrue(rewritten.contains("Path=/workbench/" + instanceId));
        assertFalse(rewritten.toLowerCase().contains("domain="));
        assertFalse(rewritten.toLowerCase().contains("secure"));
        assertTrue(rewritten.contains("HttpOnly"));
    }

    @Test
    @Order(5)
    void proxiesHtmlPostBodyAssetHeadersAndRewritesOrigin() throws Exception {
        HttpResponse<String> page = client.send(
                request("/workbench/" + instanceId + "/", true).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("workbench-ok"));

        // POST 带 body：验证 CSRF 豁免 + 流式请求体透传
        HttpRequest echo = request("/workbench/" + instanceId + "/echo", true)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("hello-proxy"))
                .build();
        HttpResponse<String> echoed = client.send(echo, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, echoed.statusCode());
        assertEquals("echo:hello-proxy", echoed.body());

        CAPTURED_HOST.set(null);
        CAPTURED_ORIGIN.set(null);
        CAPTURED_COOKIE.set(null);
        HttpRequest asset = request("/workbench/" + instanceId + "/assets/a.js", true)
                .header("Origin", "http://public.example")
                .header("Accept", "application/javascript")
                .GET()
                .build();
        HttpResponse<String> js = client.send(asset, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, js.statusCode());
        assertTrue(js.body().contains("console.log"));
        assertEquals("127.0.0.1:" + dshPort, CAPTURED_HOST.get(), "上游 Host 必须为回环地址+实例端口");
        assertEquals("http://127.0.0.1:" + dshPort, CAPTURED_ORIGIN.get(), "Origin 必须改写为上游来源");
        assertNotNull(CAPTURED_COOKIE.get());
        assertTrue(CAPTURED_COOKIE.get().contains("dsh-auth-abcdef12"));
        assertFalse(CAPTURED_COOKIE.get().contains("JSESSIONID"), "本应用会话 Cookie 不得泄漏给实例进程");
    }

    @Test
    @Order(6)
    void rewritesAbsoluteLocation() throws Exception {
        HttpResponse<String> response = client.send(
                request("/workbench/" + instanceId + "/go", true).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(302, response.statusCode());
        assertEquals("/workbench/" + instanceId + "/landing?x=1",
                response.headers().firstValue("Location").orElse(""));
    }

    @Test
    @Order(7)
    void streamsSseWithoutBuffering() throws Exception {
        HttpRequest request = request("/workbench/" + instanceId + "/events", true)
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        long start = System.nanoTime();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String firstData = null;
            long firstAt = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    firstData = line;
                    firstAt = System.nanoTime();
                    break;
                }
            }
            assertEquals("data: first", firstData);
            long elapsedMillis = (firstAt - start) / 1_000_000L;
            assertTrue(elapsedMillis < 650L,
                    "SSE 首事件必须在 mock 的 700ms 延迟前到达（实际 " + elapsedMillis + "ms）");
            String secondData = null;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    secondData = line;
                    break;
                }
            }
            assertEquals("data: second", secondData);
        }
    }

    @Test
    @Order(8)
    void mapsUpstreamTimeoutTo504() throws Exception {
        properties.setProxyTimeout(Duration.ofMillis(300));
        try {
            HttpResponse<String> response = client.send(
                    request("/workbench/" + instanceId + "/slow", true).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(504, response.statusCode());
            assertEquals("UPSTREAM_TIMEOUT", json.readTree(response.body()).path("code").asText());
        } finally {
            properties.setProxyTimeout(Duration.ofSeconds(60));
        }
    }

    @Test
    @Order(9)
    void webUrlReflectsProxySetting() throws Exception {
        HttpResponse<String> proxied = client.send(
                request("/api/instances/" + instanceId + "/web-url", false).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, proxied.statusCode());
        JsonNode body = json.readTree(proxied.body());
        assertEquals("/workbench/" + instanceId + "/", body.path("url").asText());

        properties.setProxyEnabled(false);
        try {
            HttpResponse<String> direct = client.send(
                    request("/api/instances/" + instanceId + "/web-url", false).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, direct.statusCode());
            assertEquals("http://127.0.0.1:" + dshPort + "/?token=m0-token",
                    json.readTree(direct.body()).path("url").asText());
        } finally {
            properties.setProxyEnabled(true);
        }
    }

    @Test
    @Order(10)
    void upstreamUnavailableMapsTo502() throws Exception {
        dsh.stop(0);
        HttpResponse<String> response = client.send(
                request("/workbench/" + instanceId + "/", true).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(502, response.statusCode());
        assertEquals("UPSTREAM_ERROR", json.readTree(response.body()).path("code").asText());
    }

    // ------------------------------------------------------------------ mock DSH

    private void runMockDsh() throws IOException {
        dsh = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        dsh.createContext("/", this::handleMockDsh);
        dshExecutor = Executors.newCachedThreadPool();
        dsh.setExecutor(dshExecutor);
        dsh.start();
        dshPort = dsh.getAddress().getPort();
    }

    private void handleMockDsh(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            switch (path) {
                case "/" -> handleMockRoot(exchange);
                case "/assets/a.js" -> {
                    CAPTURED_HOST.set(exchange.getRequestHeaders().getFirst("Host"));
                    CAPTURED_ORIGIN.set(exchange.getRequestHeaders().getFirst("Origin"));
                    CAPTURED_COOKIE.set(exchange.getRequestHeaders().getFirst("Cookie"));
                    respond(exchange, 200, "application/javascript", "console.log('asset');");
                }
                case "/events" -> handleMockEvents(exchange);
                case "/echo" -> {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    respond(exchange, 200, "text/plain", "echo:" + new String(body, StandardCharsets.UTF_8));
                }
                case "/go" -> {
                    exchange.getResponseHeaders().add("Location",
                            "http://127.0.0.1:" + dshPort + "/landing?x=1");
                    exchange.sendResponseHeaders(302, -1);
                }
                case "/landing" -> respond(exchange, 200, "text/plain", "landing");
                case "/slow" -> {
                    sleep(800L);
                    respond(exchange, 200, "text/plain", "slow");
                }
                default -> respond(exchange, 404, "text/plain", "not-found");
            }
        } catch (RuntimeException failure) {
            try {
                exchange.close();
            } catch (RuntimeException ignored) {
                // 已关闭。
            }
        }
    }

    private void handleMockRoot(HttpExchange exchange) throws IOException {
        String token = queryParameter(exchange, "token");
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (token != null && !token.isEmpty()) {
            exchange.getResponseHeaders().add("Set-Cookie",
                    "dsh-auth-abcdef12=cookie-value; HttpOnly; Secure; Domain=127.0.0.1; Path=/; SameSite=None");
            exchange.getResponseHeaders().add("Location", "./");
            exchange.sendResponseHeaders(303, -1);
            exchange.close();
            return;
        }
        if (cookie != null && cookie.contains(DSH_COOKIE)) {
            respond(exchange, 200, "text/html", "<html>workbench-ok</html>");
            return;
        }
        respond(exchange, 401, "text/plain", "dsh web authentication required");
    }

    private void handleMockEvents(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write("data: first\n\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
            sleep(700L);
            output.write("data: second\n\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException ignored) {
            // 客户端断开。
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String queryParameter(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            String key = equals < 0 ? part : part.substring(0, equals);
            if (key.equals(name)) {
                return equals < 0 ? "" : part.substring(equals + 1);
            }
        }
        return null;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private HttpRequest.Builder request(String path, boolean withDshCookie) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path));
        StringBuilder cookie = new StringBuilder(sessionCookie == null ? "" : sessionCookie);
        if (withDshCookie) {
            if (cookie.length() > 0) {
                cookie.append("; ");
            }
            cookie.append(DSH_COOKIE);
        }
        if (cookie.length() > 0) {
            builder.header("Cookie", cookie.toString());
        }
        return builder;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + appPort;
    }
}
