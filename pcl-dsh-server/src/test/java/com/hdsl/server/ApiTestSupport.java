package com.hdsl.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后端 API 集成测试公共辅助：完成“登录 → 首次强制改密 → 保存会话/CSRF”，
 * 并提供带会话 + CSRF 的请求构造器（写接口必须同时带 Cookie 与请求头）。
 */
abstract class ApiTestSupport {

    static final String NEW_PASSWORD = "ApiTestPass!2026";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    private MockHttpSession session;
    private Cookie xsrf;
    private String csrf;

    /** 幂等登录：首次调用完成登录与强制改密，后续测试复用会话与 CSRF Token。 */
    protected void authenticate(Path dataRoot) throws Exception {
        if (session != null) {
            return;
        }
        // 首次运行读 initial-password；同一数据目录的后续测试（改密成功后文件已删除）用新密码登录。
        Path initialFile = dataRoot.resolve("initial-password");
        String initial = Files.isRegularFile(initialFile) ? Files.readString(initialFile).strip() : NEW_PASSWORD;
        MvcResult login = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(initial)))
                .andExpect(status().isNoContent())
                .andReturn();
        session = (MockHttpSession) login.getRequest().getSession(false);
        assertNotNull(session, "登录成功必须建立会话");
        MvcResult me = mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json.readTree(me.getResponse().getContentAsString());
        csrf = body.path("csrfToken").asText();
        xsrf = me.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(xsrf, "必须下发 XSRF-TOKEN Cookie");
        if (body.path("initialPasswordPending").asBoolean()) {
            mvc.perform(put("/api/auth/password").session(session).cookie(xsrf).header("X-XSRF-TOKEN", csrf)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(passwordJson(initial, NEW_PASSWORD)))
                    .andExpect(status().isNoContent());
        }
    }

    /** 已登录 + CSRF 的写请求（POST/PUT/DELETE）。 */
    protected MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder builder) {
        return builder.session(session).cookie(xsrf).header("X-XSRF-TOKEN", csrf);
    }

    /** 已登录的读请求。 */
    protected MockHttpServletRequestBuilder read(MockHttpServletRequestBuilder builder) {
        return builder.session(session);
    }

    protected MockHttpSession session() {
        return session;
    }

    protected String body(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    protected String credentialsJson(String password) throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("password", password);
        return json.writeValueAsString(value);
    }

    protected String passwordJson(String oldPassword, String newPassword) throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("oldPassword", oldPassword);
        value.put("newPassword", newPassword);
        return json.writeValueAsString(value);
    }
}
