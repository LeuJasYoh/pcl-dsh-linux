package com.hdsl.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.hdsl.core.InstanceStore;
import com.hdsl.server.config.DataRoot;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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
 * 运行时与日志接口集成测试：可用版本查询的 502 中文错误路径、安装/探测/删除的参数校验与
 * 404/409 分支、运行时引用保护、日志尾部读取的行数限制与脱敏。
 *
 * <p>真实安装（pnpm + npm 公网）与真实 launch 不在自动化范围内：需要 Node/pnpm 与网络，
 * 由 M0/服务器验收覆盖；这里覆盖同步参数校验与冲突分支。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuntimeLogApiTest extends ApiTestSupport {

    private static final Path DATA_ROOT = TestDataRoots.prepare("runtime-log-api");

    @DynamicPropertySource
    static void dataRoot(DynamicPropertyRegistry registry) {
        registry.add("pcl.data-root", DATA_ROOT::toString);
    }

    @Autowired
    private DataRoot data;
    @Autowired
    private InstanceStore store;
    @Autowired
    private JobManager jobs;

    @BeforeEach
    void setUp() throws Exception {
        authenticate(DATA_ROOT);
        awaitIdle();
    }

    @Test
    @Order(1)
    void logsTailIsEmptyWhenNoApplicationLogExists() throws Exception {
        mvc.perform(read(get("/api/logs/tail")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines").isArray())
                .andExpect(jsonPath("$.lines").isEmpty());
    }

    @Test
    @Order(2)
    void runtimeInstallProbeAndDeleteValidateParameters() throws Exception {
        // 版本格式非法 → 400 VALIDATION
        mvc.perform(write(post("/api/runtimes"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("version", "not-a-version"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.message").value(containsString("Harness 版本号")));
        // 缺少版本 → 400
        mvc.perform(write(post("/api/runtimes")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        // 探测未安装版本 → 404（不会提交任务）
        mvc.perform(write(post("/api/runtimes/0.2.0-rc.6/probe")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        // 删除未安装版本 → 404
        mvc.perform(write(delete("/api/runtimes/0.2.0-rc.6")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("运行时未安装")));
        // 已安装列表：空数组
        mvc.perform(read(get("/api/runtimes")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        // 权限分支：匿名访问运行时与日志接口 → 401
        mvc.perform(get("/api/runtimes/available"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/logs/tail"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(3)
    void availableVersionsReturnsChineseUpstreamErrorWhenRegistryUnreachable() throws Exception {
        // 指向本机未监听端口：连接应立即失败，不等待 60 秒。
        mvc.perform(write(put("/api/settings"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("proxy", "", "registry", "http://127.0.0.1:1", "background", ""))))
                .andExpect(status().isOk());
        try {
            MvcResult result = mvc.perform(read(get("/api/runtimes/available")))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("UPSTREAM_ERROR"))
                    .andReturn();
            String message = json.readTree(result.getResponse().getContentAsString()).path("message").asText();
            assertTrue(message.matches(".*[\\u4e00-\\u9fff].*"), "上游错误提示应为中文：" + message);
        } finally {
            // 恢复默认 registry，避免影响后续任务与测试
            mvc.perform(write(put("/api/settings"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("proxy", "", "registry", "https://registry.npmjs.org", "background", ""))))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @Order(4)
    void deletingRuntimeReferencedByInstanceReturnsConflictInUse() throws Exception {
        MvcResult created = mvc.perform(write(post("/api/instances"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "引用运行时实例", "version", "0.3.0"))))
                .andExpect(status().isCreated())
                .andReturn();
        String id = json.readTree(created.getResponse().getContentAsString()).path("id").asText();
        try {
            mvc.perform(write(delete("/api/runtimes/0.3.0")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT_IN_USE"))
                    .andExpect(jsonPath("$.message").value(containsString("有实例引用此运行时")));
        } finally {
            store.remove(id);
        }
    }

    @Test
    @Order(5)
    void logsTailReturnsRedactedBoundedLines() throws Exception {
        Path file = data.logs().resolve("launcher-" + LocalDate.now() + ".log");
        List<String> content = List.of(
                "10:00:00  第一行普通日志",
                "10:00:01  已创建实例：测试",
                "10:00:02  apiKey=SECRETVALUE123",
                "10:00:03  Authorization: Bearer SECRETTOKEN",
                "10:00:04  最后一行");
        Files.write(file, content, StandardCharsets.UTF_8);

        MvcResult result = mvc.perform(read(get("/api/logs/tail").param("lines", "3")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines").isArray())
                .andExpect(jsonPath("$.lines.length()").value(3))
                .andReturn();
        JsonNode lines = json.readTree(result.getResponse().getContentAsString()).path("lines");
        assertEquals(3, lines.size());
        String joined = lines.toString();
        assertFalse(joined.contains("SECRETVALUE123"), "日志尾部不得回传 API Key 原文");
        assertFalse(joined.contains("SECRETTOKEN"), "日志尾部不得回传 token 原文");
        assertTrue(joined.contains("[已隐藏]"), "敏感字段应替换为 [已隐藏]");
        assertTrue(lines.get(2).asText().contains("最后一行"), "应返回文件末尾行");

        // lines 越界按边界处理：≤0 使用默认 800，超上限截断到 2000；文件只有 5 行。
        mvc.perform(read(get("/api/logs/tail").param("lines", "0")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(5));
        mvc.perform(read(get("/api/logs/tail").param("lines", "100000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(5));
        // 默认参数同样可用
        mvc.perform(read(get("/api/logs/tail")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(5));
    }

    private void awaitIdle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (jobs.busy() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertFalse(jobs.busy(), "测试开始前应处于空闲状态");
    }
}
