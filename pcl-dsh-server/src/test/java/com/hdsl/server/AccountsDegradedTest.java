package com.hdsl.server;

import com.hdsl.server.job.JobManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 账户资料损坏降级：GET /api/accounts 返回 200 + 空数组 + 警告头；GET /api/state 返回
 * accounts=[] + warnings 中文提示；并写入 ACCOUNTS_DEGRADED 审计（不 500、不泄漏文件路径）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountsDegradedTest extends ApiTestSupport {

    private static final Path DATA_ROOT = TestDataRoots.prepare("accounts-degraded");

    @DynamicPropertySource
    static void dataRoot(DynamicPropertyRegistry registry) {
        registry.add("pcl.data-root", DATA_ROOT::toString);
    }

    @Autowired
    private JobManager jobs;

    @BeforeAll
    void corruptAccountsFile() throws Exception {
        Path config = DATA_ROOT.resolve("config");
        Files.createDirectories(config);
        Files.writeString(config.resolve("accounts.json"), "{ this is not valid accounts json",
                StandardCharsets.UTF_8);
    }

    @BeforeEach
    void setUp() throws Exception {
        authenticate(DATA_ROOT);
        awaitIdle();
    }

    @Test
    @Order(1)
    void accountsEndpointDegradesToEmptyListWithout500() throws Exception {
        mvc.perform(read(get("/api/accounts")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-PCL-Warning", "ACCOUNTS_UNREADABLE"))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
        // 账户密钥/文件路径等内部细节不得出现在响应中
        String audit = auditText();
        assertTrue(audit.contains("ACCOUNTS_DEGRADED"), "账户资料损坏必须记录审计：\n" + audit);
        assertFalse(audit.contains(DATA_ROOT.toString()), "审计不得包含数据目录绝对路径");
    }

    @Test
    @Order(2)
    void stateIncludesWarningAndEmptyAccounts() throws Exception {
        mvc.perform(read(get("/api/state")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts").isArray())
                .andExpect(jsonPath("$.accounts.length()").value(0))
                .andExpect(jsonPath("$.warnings").isArray())
                .andExpect(jsonPath("$.warnings[0]", containsString("账户资料")));
    }

    private String auditText() throws Exception {
        Path audit = DATA_ROOT.resolve("logs").resolve("audit-" + LocalDate.now() + ".log");
        if (!Files.isRegularFile(audit)) {
            return "";
        }
        try (Stream<String> lines = Files.lines(audit, StandardCharsets.UTF_8)) {
            return String.join("\n", lines.toList());
        }
    }

    private void awaitIdle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (jobs.busy() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertFalse(jobs.busy(), "测试开始前应处于空闲状态");
    }
}
