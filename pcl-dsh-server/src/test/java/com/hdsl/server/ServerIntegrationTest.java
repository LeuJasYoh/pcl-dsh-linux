package com.hdsl.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后端 M1 集成测试：上下文加载、健康检查、未登录 401、登录/强制改密/CSRF 全流程、登录限流、审计脱敏。
 * 使用 target/test-data 下的独立数据目录（@DynamicPropertySource），不读写 ./data。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ServerIntegrationTest {

    private static final Path DATA_ROOT = TestDataRoots.prepare("auth-flow");
    private static final String NEW_PASSWORD = "NewStrongPass!2026";

    @DynamicPropertySource
    static void dataRoot(DynamicPropertyRegistry registry) {
        registry.add("pcl.data-root", DATA_ROOT::toString);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private TestRestTemplate rest;

    @Test
    @Order(0)
    void realHttpServerStartsAndServesHealthAnonymously() throws Exception {
        // 真实内嵌 Tomcat（随机端口）：验证启动自检、Security 过滤器链与 JSON 错误在真实容器中生效。
        var health = rest.getForEntity("/api/health", com.hdsl.server.api.ApiDtos.HealthDto.class);
        assertEquals(200, health.getStatusCode().value());
        assertNotNull(health.getBody());
        assertEquals("UP", health.getBody().status());
        var unauthorized = rest.getForEntity("/api/state", String.class);
        assertEquals(401, unauthorized.getStatusCode().value());
        assertTrue(unauthorized.getBody() != null && unauthorized.getBody().contains("UNAUTHORIZED"));
        assertNotNull(unauthorized.getHeaders().getFirst("X-Trace-Id"));

        // 真实容器下校验会话 Cookie：JSESSIONID 必须 HttpOnly + SameSite=Lax，且下发 XSRF-TOKEN。
        String initial = Files.readString(DATA_ROOT.resolve("initial-password")).strip();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var login = rest.postForEntity("/api/auth/login",
                new HttpEntity<>(credentialsJson(initial), headers), Void.class);
        assertEquals(204, login.getStatusCode().value());
        var cookies = login.getHeaders().get("Set-Cookie");
        assertNotNull(cookies, "登录响应必须设置会话 Cookie");
        String sessionCookie = cookies.stream().filter(c -> c.startsWith("JSESSIONID=")).findFirst().orElse(null);
        assertNotNull(sessionCookie, "登录成功必须下发 JSESSIONID");
        assertTrue(sessionCookie.contains("HttpOnly"), "JSESSIONID 必须 HttpOnly");
        assertTrue(sessionCookie.toUpperCase().contains("SAMESITE=LAX"), "JSESSIONID 必须 SameSite=Lax");
        assertTrue(cookies.stream().anyMatch(c -> c.startsWith("XSRF-TOKEN=")), "必须下发 XSRF-TOKEN Cookie");
    }

    @Test
    @Order(1)
    void contextLoadsAndHealthIsAnonymous() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.version").value("0.3.0"))
                .andExpect(jsonPath("$.uptimeSeconds").isNumber());
        // 首次启动生成 server.json 与初始密码文件（位于数据根，不在 config 下）
        assertTrue(Files.isRegularFile(DATA_ROOT.resolve("config/server.json")));
        Path initialFile = DATA_ROOT.resolve("initial-password");
        assertTrue(Files.isRegularFile(initialFile));
        String initial = Files.readString(initialFile).strip();
        assertTrue(initial.length() >= 20, "初始密码应为随机强密码");
        // 初始密码不得出现在审计日志中
        Path audit = DATA_ROOT.resolve("logs/audit-" + LocalDate.now() + ".log");
        assertFalse(Files.exists(audit) && Files.readString(audit).contains(initial));
    }

    @Test
    @Order(2)
    void protectedApiWithoutLoginReturnsJson401() throws Exception {
        // 匿名探测登录态的接口：不 401，返回 authenticated=false
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false));
        mvc.perform(get("/api/state"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        mvc.perform(get("/api/instances")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/settings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/jobs")).andExpect(status().isUnauthorized());
    }

    @Test
    @Order(3)
    void loginForcesPasswordChangeAndCsrfIsEnforced() throws Exception {
        String initial = Files.readString(DATA_ROOT.resolve("initial-password")).strip();

        MvcResult login = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(initial)))
                .andExpect(status().isNoContent())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertNotNull(session, "登录成功必须建立会话");

        MvcResult me = mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.initialPasswordPending").value(true))
                .andExpect(jsonPath("$.csrfToken").isNotEmpty())
                .andReturn();
        String csrf = json.readTree(me.getResponse().getContentAsString()).path("csrfToken").asText();
        Cookie xsrf = me.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(xsrf, "GET /api/auth/me 必须下发 XSRF-TOKEN Cookie");
        assertEquals(csrf, xsrf.getValue(), "CSRF Token 与 Cookie 值应一致（双提交模式）");

        // 初始密码未修改时，业务接口一律 403 PASSWORD_CHANGE_REQUIRED
        mvc.perform(get("/api/state").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));

        // 缺少 CSRF 头 → 403 CSRF_INVALID（JSON，不重定向）
        mvc.perform(put("/api/auth/password").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson(initial, NEW_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        // 携带 Cookie + 请求头 → 204
        mvc.perform(put("/api/auth/password").session(session)
                        .cookie(xsrf)
                        .header("X-XSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson(initial, NEW_PASSWORD)))
                .andExpect(status().isNoContent());
        assertFalse(Files.exists(DATA_ROOT.resolve("initial-password")), "改密成功后应删除 initial-password");

        // 改密后状态接口可用，字段符合契约
        mvc.perform(get("/api/state").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("0.3.0"))
                .andExpect(jsonPath("$.status.code").value("READY"))
                .andExpect(jsonPath("$.settings.registry").value("https://registry.npmjs.org"))
                .andExpect(jsonPath("$.instances").isArray())
                .andExpect(jsonPath("$.runtimes").isArray());
        mvc.perform(get("/api/runtimes").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        // 未知 /api 路径：统一 JSON 404（不重定向、不返回 Whitelabel HTML）
        mvc.perform(get("/api/does-not-exist").session(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        // 非法 JSON 请求体：400 BAD_REQUEST（中文提示）
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        // 旧密码失效（401），新密码可再次登录
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(initial)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
        MvcResult relogin = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(NEW_PASSWORD)))
                .andExpect(status().isNoContent())
                .andReturn();
        MockHttpSession fresh = (MockHttpSession) relogin.getRequest().getSession(false);
        mvc.perform(get("/api/state").session(fresh)).andExpect(status().isOk());
    }

    @Test
    @Order(4)
    void settingsUpdateRequiresCsrfAndValidatesRegistry() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(NEW_PASSWORD)))
                .andExpect(status().isNoContent())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        MvcResult me = mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk()).andReturn();
        String csrf = json.readTree(me.getResponse().getContentAsString()).path("csrfToken").asText();
        Cookie xsrf = me.getResponse().getCookie("XSRF-TOKEN");

        // 非法 registry → 400 VALIDATION（中文可操作提示）
        mvc.perform(put("/api/settings").session(session).cookie(xsrf).header("X-XSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proxy\":\"\",\"registry\":\"not-a-url\",\"background\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.message").isNotEmpty());

        // 合法设置 → 200 并回显
        mvc.perform(put("/api/settings").session(session).cookie(xsrf).header("X-XSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proxy\":\"\",\"registry\":\"https://registry.npmmirror.com\",\"background\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registry").value("https://registry.npmmirror.com"));
        mvc.perform(get("/api/settings").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registry").value("https://registry.npmmirror.com"));
    }

    @Test
    @Order(5)
    void loginFailuresAreRateLimitedAndAudited() throws Exception {
        for (int attempt = 1; attempt <= 4; attempt++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(credentialsJson("definitely-wrong-password")))
                    .andExpect(status().isUnauthorized());
        }
        // 第 5 次失败触发锁定（423）
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson("definitely-wrong-password")))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("LOGIN_LOCKED"))
                .andExpect(jsonPath("$.details.retryAfterSeconds").isNumber());
        // 锁定窗口内即使密码正确也拒绝
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(NEW_PASSWORD)))
                .andExpect(status().isLocked());

        Path audit = DATA_ROOT.resolve("logs/audit-" + LocalDate.now() + ".log");
        assertTrue(Files.isRegularFile(audit), "审计日志必须存在");
        String text = Files.readString(audit);
        assertTrue(text.contains("LOGIN_SUCCESS"));
        assertTrue(text.contains("LOGIN_FAILURE"));
        assertTrue(text.contains("LOGIN_LOCKED"));
        assertFalse(text.contains(NEW_PASSWORD), "审计日志不得包含密码");
        assertFalse(text.contains("definitely-wrong-password"), "审计日志不得包含密码");
    }

    private String credentialsJson(String password) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("password", password);
        return json.writeValueAsString(body);
    }

    private String passwordJson(String oldPassword, String newPassword) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("oldPassword", oldPassword);
        body.put("newPassword", newPassword);
        return json.writeValueAsString(body);
    }
}
