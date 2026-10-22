package com.hdsl.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.hdsl.account.AccountEntry;
import com.hdsl.account.AccountService;
import com.hdsl.core.Instance;
import com.hdsl.core.InstanceStore;
import com.hdsl.core.ProcessService;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 实例 REST 集成测试（MockMvc + 临时数据目录）：创建/校验、单实例读取、编辑限制（version/profile
 * 409）、选择、账户绑定、运行中实例的保护与停止、launch 的同步拒绝分支（404/409）、忙时 409、
 * 删除移入 trash。
 *
 * <p>真实 launch/install 需要 Node/pnpm 与网络，本测试不执行；其参数校验与 409 分支已覆盖，
 * 详见任务报告。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InstanceApiTest extends ApiTestSupport {

    private static final Path DATA_ROOT = TestDataRoots.prepare("instance-api");
    private static String instanceA;
    private static String instanceB;
    private static int portA;

    @DynamicPropertySource
    static void dataRoot(DynamicPropertyRegistry registry) {
        registry.add("pcl.data-root", DATA_ROOT::toString);
    }

    @Autowired
    private InstanceStore store;
    @Autowired
    private ProcessService processes;
    @Autowired
    private AccountService accounts;
    @Autowired
    private JobManager jobs;
    @Autowired
    private DataRoot data;

    @BeforeEach
    void setUp() throws Exception {
        authenticate(DATA_ROOT);
        awaitIdle();
    }

    @Test
    @Order(1)
    void createInstanceValidatesInputAndPersistsDirectories() throws Exception {
        // 写接口缺少 CSRF → 403 CSRF_INVALID
        mvc.perform(post("/api/instances").session(session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "无CSRF", "version", "0.1.0-rc.6"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        // 名称必填
        expectCreateValidation(Map.of("name", "", "version", "0.1.0-rc.6"), "实例名称");
        // 版本格式
        expectCreateValidation(Map.of("name", "版本非法", "version", "not-a-version"), "精确版本号");
        // 端口范围
        expectCreateValidation(Map.of("name", "端口非法", "version", "0.1.0-rc.6", "port", "80"), "1024");
        // 端口不是整数
        expectCreateValidation(Map.of("name", "端口非整数", "version", "0.1.0-rc.6", "port", "abc"), "整数");
        // 空白实例只允许 web profile（桌面版文案）
        expectCreateValidation(Map.of("name", "自定义档案", "version", "0.1.0-rc.6", "profile", "custom"),
                "空白实例使用 web 模板");
        // 账户必须存在
        expectCreateValidation(Map.of("name", "账户不存在", "version", "0.1.0-rc.6", "accountId", "no-such-account"),
                "所选账户不存在");

        // 创建实例 A（自动分配端口）
        MvcResult createdA = mvc.perform(write(post("/api/instances"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "测试实例A", "version", "0.1.0-rc.6", "profile", "web"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(containsString("i-")))
                .andExpect(jsonPath("$.profile").value("web"))
                .andExpect(jsonPath("$.version").value("0.1.0-rc.6"))
                .andReturn();
        JsonNode nodeA = json.readTree(createdA.getResponse().getContentAsString());
        instanceA = nodeA.path("id").asText();
        portA = nodeA.path("port").asInt();
        assertTrue(portA >= 3080 && portA <= 65535, "自动分配端口应在 3080–65535：" + portA);
        Instance storedA = store.find(instanceA);
        assertTrue(Files.isDirectory(store.home(storedA)), "创建实例必须建立 dsh-home");
        assertTrue(Files.isDirectory(store.workspace(storedA)), "创建实例必须建立 workspace");

        // 端口与其他实例重复 → 400
        mvc.perform(write(post("/api/instances"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "重复端口", "version", "0.1.0-rc.6", "port", portA))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.message").value("端口已被其他实例使用。"));

        // 创建实例 B（省略 profile → 默认 web，自动端口）
        MvcResult createdB = mvc.perform(write(post("/api/instances"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "测试实例B", "version", "0.1.0-rc.6"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.profile").value("web"))
                .andReturn();
        JsonNode nodeB = json.readTree(createdB.getResponse().getContentAsString());
        instanceB = nodeB.path("id").asText();
        assertTrue(nodeB.path("port").asInt() != portA, "自动分配端口不得与其他实例冲突");

        // 列表包含两个实例
        mvc.perform(read(get("/api/instances")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @Order(2)
    void getSingleInstanceAndUnknownId() throws Exception {
        mvc.perform(read(get("/api/instances/" + instanceA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(instanceA))
                .andExpect(jsonPath("$.profiles").isArray());
        mvc.perform(read(get("/api/instances/i-000000000000")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(read(get("/api/instances/bad!id")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));

        // 权限分支：匿名 GET → 401（JSON，不重定向）；匿名写请求先被 CSRF 过滤器拒绝 → 403 CSRF_INVALID
        mvc.perform(get("/api/instances/" + instanceA))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/instances/" + instanceA + "/web-url"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/instances/" + instanceA + "/stop"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        // 按 Web 安全要求不提供 customCommand 入口 → 404
        mvc.perform(write(post("/api/instances/" + instanceA + "/custom-command"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("command", "echo hi"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @Order(3)
    void updateOnlySupportsNamePortAccountAndRejectsVersionProfile() throws Exception {
        // version / profile 变更属于下个增量 → 409 中文提示
        mvc.perform(write(put("/api/instances/" + instanceA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "测试实例A", "version", "9.9.9"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT_UNSUPPORTED_CHANGE"))
                .andExpect(jsonPath("$.message").value(containsString("下个增量")));
        mvc.perform(write(put("/api/instances/" + instanceA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "测试实例A", "profile", "custom"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT_UNSUPPORTED_CHANGE"));

        // 名称超长 → 400
        mvc.perform(write(put("/api/instances/" + instanceA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "x".repeat(200)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));

        // 端口与其他实例冲突 → 400
        int portB = store.find(instanceB).port();
        mvc.perform(write(put("/api/instances/" + instanceA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "测试实例A", "port", portB))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("端口已分配给其他实例。"));

        // 账户不存在 → 400
        mvc.perform(write(put("/api/instances/" + instanceA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "测试实例A", "accountId", "no-such-account"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));

        // 合法修改：名称 + 端口（显式空闲端口）
        int newPort = freePort();
        mvc.perform(write(put("/api/instances/" + instanceA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "改名实例A", "port", newPort, "accountId", ""))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("改名实例A"))
                .andExpect(jsonPath("$.port").value(newPort));
        portA = newPort;
        mvc.perform(read(get("/api/instances/" + instanceA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("改名实例A"))
                .andExpect(jsonPath("$.port").value(newPort));

        // 账户绑定 / 解绑（账户 REST 属后续增量，这里直接用 core 的 AccountService 造数据）
        AccountEntry account = accounts.save("", "测试账户", "deepseek-official", "", "deepseek-flash",
                "sk-test-abcdefghijklmnop");
        mvc.perform(write(put("/api/instances/" + instanceA + "/account"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("accountId", account.id()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(account.id()));
        mvc.perform(write(put("/api/instances/" + instanceA + "/account"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("accountId", ""))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(""));
        mvc.perform(write(put("/api/instances/" + instanceA + "/account"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("accountId", "no-such-account"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("所选账户不存在，请重新选择。"));
    }

    @Test
    @Order(4)
    void selectionUpdatesCurrentInstance() throws Exception {
        mvc.perform(write(put("/api/instances/" + instanceB + "/selection")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(instanceB));
        mvc.perform(read(get("/api/state")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentInstanceId").value(instanceB));
        mvc.perform(write(put("/api/instances/i-000000000000/selection")))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(5)
    void runningInstanceBlocksEditingAndDeletingButCanStop() throws Exception {
        startFixtureProcess(instanceA);
        assertTrue(processes.running(instanceA), "测试夹具进程应处于运行状态");

        mvc.perform(write(put("/api/instances/" + instanceA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "运行中改名"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT_INSTANCE_RUNNING"));
        mvc.perform(write(put("/api/instances/" + instanceA + "/account"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("accountId", ""))))
                .andExpect(status().isConflict());
        mvc.perform(write(delete("/api/instances/" + instanceA)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT_INSTANCE_RUNNING"));
        // 运行中但未就绪 → 409 INSTANCE_NOT_READY
        mvc.perform(read(get("/api/instances/" + instanceA + "/web-url")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSTANCE_NOT_READY"));

        // 停止不受忙任务限制，返回最新状态
        mvc.perform(write(post("/api/instances/" + instanceA + "/stop")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("未启动"));
        assertFalse(processes.running(instanceA));
        // 未运行实例 → 409 INSTANCE_NOT_READY
        mvc.perform(read(get("/api/instances/" + instanceA + "/web-url")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSTANCE_NOT_READY"))
                .andExpect(jsonPath("$.message").value("请先启动当前实例。"));
    }

    @Test
    @Order(6)
    void launchRejectsRunningInstanceCustomCommandAndUnknownInstance() throws Exception {
        mvc.perform(write(post("/api/instances/i-000000000000/launch")))
                .andExpect(status().isNotFound());

        startFixtureProcess(instanceB);
        try {
            // 存在其他运行中实例 → 409 CONFLICT_RUNNING_INSTANCE（单运行实例限制）
            mvc.perform(write(post("/api/instances/" + instanceA + "/launch")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT_RUNNING_INSTANCE"))
                    .andExpect(jsonPath("$.message").value(containsString("同时最多运行 1 个实例")));
            // 目标实例自身运行中 → 409 CONFLICT_INSTANCE_RUNNING（不会停止已有进程）
            mvc.perform(write(post("/api/instances/" + instanceB + "/launch")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT_INSTANCE_RUNNING"));
        } finally {
            processes.stop(instanceB, line -> { });
        }

        // 非空 customCommand → 明确拒绝（Web 安全要求）
        Instance legacy = new Instance(InstanceStore.newId(), "旧命令实例", "0.1.0-rc.6", "web", freePort(), "echo should-not-run");
        Files.createDirectories(store.home(legacy));
        Files.createDirectories(store.workspace(legacy));
        store.add(legacy);
        try {
            mvc.perform(write(post("/api/instances/" + legacy.id() + "/launch")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT_CUSTOM_COMMAND"))
                    .andExpect(jsonPath("$.message").value(containsString("不执行自定义命令")));
        } finally {
            store.remove(legacy.id());
        }
    }

    @Test
    @Order(7)
    void busyJobMakesSynchronousWritesReturnConflict() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        jobs.submit("GATE_TEST", java.time.Duration.ofSeconds(30), context -> {
            context.checkCancelled();
            release.await();
            return Map.of("ok", true);
        });
        try {
            mvc.perform(write(post("/api/instances"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("name", "忙时创建", "version", "0.1.0-rc.6"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT_BUSY"));
            mvc.perform(write(put("/api/instances/" + instanceA + "/selection")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT_BUSY"));
            mvc.perform(write(post("/api/instances/" + instanceA + "/launch")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT_BUSY"));
            mvc.perform(write(delete("/api/instances/" + instanceB)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT_BUSY"));
        } finally {
            release.countDown();
            awaitIdle();
        }
    }

    @Test
    @Order(8)
    void deleteMovesInstanceDirectoryToTrash() throws Exception {
        Path marker = store.workspace(store.find(instanceB)).resolve("marker.txt");
        Files.writeString(marker, "trash-me");
        MvcResult result = mvc.perform(write(delete("/api/instances/" + instanceB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trashedPath").isNotEmpty())
                .andReturn();
        String trashedPath = json.readTree(result.getResponse().getContentAsString()).path("trashedPath").asText();
        Path trashed = Path.of(trashedPath);
        assertTrue(trashed.toAbsolutePath().normalize().startsWith(data.trash().toAbsolutePath().normalize()),
                "实例目录必须移入 data/trash：" + trashed);
        assertTrue(Files.isRegularFile(trashed.resolve("workspace").resolve("marker.txt")),
                "回收目录应保留实例文件");
        assertFalse(Files.exists(data.instances().resolve(instanceB)), "删除后实例目录不应留在 instances 下");
        mvc.perform(read(get("/api/instances/" + instanceB)))
                .andExpect(status().isNotFound());
        assertTrue(store.list().stream().noneMatch(row -> row.id().equals(instanceB)));
    }

    // ------------------------------------------------------------------ 辅助

    private void expectCreateValidation(Map<String, Object> payload, String messagePart) throws Exception {
        mvc.perform(write(post("/api/instances"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.message").value(containsString(messagePart)));
    }

    /** 用当前 JVM 启动 SleepMain 夹具进程，模拟实例进程运行（不依赖 Node/pnpm）。 */
    private void startFixtureProcess(String id) throws Exception {
        Instance instance = store.find(id);
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        Path testClasses = Path.of(SleepMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> command = List.of(java.toString(), "-cp", testClasses.toString(), SleepMain.class.getName());
        Map<String, String> environment = new HashMap<>(System.getenv());
        processes.start(instance, command, store.workspace(instance), environment, line -> { });
    }

    private int freePort() {
        Set<Integer> used = new HashSet<>();
        for (Instance row : store.list()) {
            used.add(row.port());
        }
        for (int port = 31000; port < 60000; port++) {
            if (!used.contains(port) && ProcessService.portFree(port)) {
                return port;
            }
        }
        throw new IllegalStateException("测试环境没有可用端口");
    }

    private void awaitIdle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (jobs.busy() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertFalse(jobs.busy(), "测试开始前应处于空闲状态");
    }
}
