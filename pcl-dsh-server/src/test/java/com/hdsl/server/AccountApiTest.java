package com.hdsl.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.hdsl.server.job.JobManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 账户 API 集成测试：provider 预设、CRUD、编辑留空保留密钥、被实例引用 409、
 * 忙时 409、鉴权，以及响应/审计中不得出现 API Key。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountApiTest extends ApiTestSupport {

    private static final Path DATA_ROOT = TestDataRoots.prepare("account-api");
    private static final String API_KEY = "sk-account-test-1234567890";
    private static final String CHANGED_KEY = "sk-account-test-changed-09876";

    private static String accountId;
    private static String instanceId;

    @DynamicPropertySource
    static void dataRoot(DynamicPropertyRegistry registry) {
        registry.add("pcl.data-root", DATA_ROOT::toString);
    }

    @Autowired
    private JobManager jobs;

    @BeforeEach
    void setUp() throws Exception {
        authenticate(DATA_ROOT);
        awaitIdle();
    }

    @Test
    @Order(1)
    void providersListAndAuthentication() throws Exception {
        mvc.perform(read(get("/api/accounts")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
        MvcResult providers = mvc.perform(read(get("/api/accounts/providers")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andReturn();
        JsonNode list = json.readTree(providers.getResponse().getContentAsString());
        JsonNode deepseek = null;
        for (JsonNode provider : list) {
            if ("deepseek-official".equals(provider.path("id").asText())) {
                deepseek = provider;
            }
        }
        assertTrue(deepseek != null, "必须包含 DeepSeek 预设");
        assertEquals("https://api.deepseek.com", deepseek.path("baseUrl").asText());
        assertEquals("deepseek-flash", deepseek.path("model").asText());
        // 匿名访问 → 401
        mvc.perform(get("/api/accounts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/accounts/providers")).andExpect(status().isUnauthorized());
    }

    @Test
    @Order(2)
    void createAccountValidatesInputAndNeverReturnsKey() throws Exception {
        expectCreateValidation(Map.of("name", "", "provider", "deepseek-official", "model", "deepseek-flash",
                "apiKey", API_KEY), "账户名称");
        expectCreateValidation(Map.of("name", "坏提供商", "provider", "Bad Provider", "model", "x", "apiKey", API_KEY),
                "无效提供商");
        expectCreateValidation(Map.of("name", "坏地址", "provider", "deepseek-official", "baseUrl", "ftp://example.com",
                "model", "deepseek-flash", "apiKey", API_KEY), "API 地址");
        // 新建账户必须提供 API Key
        expectCreateValidation(Map.of("name", "无密钥", "provider", "deepseek-official", "model", "deepseek-flash"),
                "API Key");

        MvcResult created = mvc.perform(write(post("/api/accounts"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "测试账户", "provider", "deepseek-official",
                                "baseUrl", "", "model", "deepseek-flash", "apiKey", API_KEY))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("测试账户"))
                .andExpect(jsonPath("$.provider").value("deepseek-official"))
                .andExpect(jsonPath("$.hasKey").value(true))
                .andReturn();
        String responseBody = created.getResponse().getContentAsString();
        assertFalse(responseBody.contains(API_KEY), "响应不得包含 API Key");
        assertFalse(responseBody.contains("encryptedKey"), "响应不得包含密文");
        accountId = json.readTree(responseBody).path("id").asText();

        MvcResult listed = mvc.perform(read(get("/api/accounts")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn();
        String listBody = listed.getResponse().getContentAsString();
        assertTrue(listBody.contains(accountId));
        assertFalse(listBody.contains(API_KEY), "账户列表不得包含 API Key");
        assertFalse(listBody.contains("encryptedKey"), "账户列表不得包含密文");
    }

    @Test
    @Order(3)
    void updateAccountKeepsKeyWhenBlankAndReturnsNotFoundForUnknown() throws Exception {
        // apiKey 留空 = 保留原密钥
        mvc.perform(write(put("/api/accounts/" + accountId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "改名账户", "provider", "deepseek-official",
                                "baseUrl", "", "model", "deepseek-flash", "apiKey", ""))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("改名账户"))
                .andExpect(jsonPath("$.hasKey").value(true));
        // 提供新密钥也可保存
        mvc.perform(write(put("/api/accounts/" + accountId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "改名账户", "provider", "deepseek-official",
                                "baseUrl", "", "model", "deepseek-flash", "apiKey", CHANGED_KEY))))
                .andExpect(status().isOk());
        // 不存在的账户 → 404；非法账户编号 → 400
        mvc.perform(write(put("/api/accounts/00000000-0000-0000-0000-000000000000"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "不存在", "provider", "deepseek-official",
                                "model", "deepseek-flash"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(write(delete("/api/accounts/not-an-account-id")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    @Order(4)
    void deleteAccountIsProtectedWhenReferencedByInstance() throws Exception {
        MvcResult created = mvc.perform(write(post("/api/instances"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "绑定账户实例", "version", "0.1.0-rc.6",
                                "accountId", accountId))))
                .andExpect(status().isCreated())
                .andReturn();
        instanceId = json.readTree(created.getResponse().getContentAsString()).path("id").asText();
        mvc.perform(write(delete("/api/accounts/" + accountId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT_IN_USE"))
                .andExpect(jsonPath("$.message").value(containsString("实例")));
        // 解除绑定后可删除
        mvc.perform(write(put("/api/instances/" + instanceId + "/account"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("accountId", ""))))
                .andExpect(status().isOk());
        mvc.perform(write(delete("/api/accounts/" + accountId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId));
        mvc.perform(read(get("/api/accounts")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @Order(5)
    void busyJobMakesAccountWritesReturnConflict() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        jobs.submit("GATE_TEST", java.time.Duration.ofSeconds(30), context -> {
            context.checkCancelled();
            release.await();
            return Map.of("ok", true);
        });
        try {
            mvc.perform(write(post("/api/accounts"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("name", "忙时创建", "provider", "deepseek-official",
                                    "model", "deepseek-flash", "apiKey", API_KEY))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT_BUSY"));
            mvc.perform(write(put("/api/accounts/00000000-0000-0000-0000-000000000000"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("name", "忙时更新", "provider", "deepseek-official",
                                    "model", "deepseek-flash"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT_BUSY"));
            mvc.perform(write(delete("/api/accounts/00000000-0000-0000-0000-000000000000")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT_BUSY"));
        } finally {
            release.countDown();
            awaitIdle();
        }
    }

    private void expectCreateValidation(Map<String, Object> payload, String messagePart) throws Exception {
        mvc.perform(write(post("/api/accounts"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.message").value(containsString(messagePart)));
    }

    private void awaitIdle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (jobs.busy() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertFalse(jobs.busy(), "测试开始前应处于空闲状态");
    }
}
