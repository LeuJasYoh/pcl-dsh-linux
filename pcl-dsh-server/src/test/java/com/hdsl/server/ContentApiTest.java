package com.hdsl.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.hdsl.core.Instance;
import com.hdsl.core.InstanceStore;
import com.hdsl.core.ProcessService;
import com.hdsl.server.artifact.ArtifactStore;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内容类 API 集成测试：插件列表/变更 Job、社区插件目录（校验 + 中文 502）、背景上传与资源读取、
 * settings.background 校验、artifact 登记/下载/404、鉴权分支。
 *
 * <p>真实插件安装（pnpm + Harness CLI）、真实 GitHub/npm 目录查询不在自动化范围：
 * 测试用运行时未安装来验证 Job 失败路径，用本机不可达代理验证 502 中文错误。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContentApiTest extends ApiTestSupport {

    private static final Path DATA_ROOT = TestDataRoots.prepare("content-api");
    private static final String DEFAULT_REGISTRY = "https://registry.npmjs.org";

    private static String instanceId;
    private static String backgroundRef;
    private static byte[] pngBytes;

    @DynamicPropertySource
    static void dataRoot(DynamicPropertyRegistry registry) {
        registry.add("pcl.data-root", DATA_ROOT::toString);
    }

    @Autowired
    private InstanceStore store;
    @Autowired
    private ProcessService processes;
    @Autowired
    private JobManager jobs;
    @Autowired
    private DataRoot data;
    @Autowired
    private ArtifactStore artifacts;

    @BeforeEach
    void setUp() throws Exception {
        authenticate(DATA_ROOT);
        awaitIdle();
    }

    @Test
    @Order(1)
    void contentApisRequireAuthentication() throws Exception {
        mvc.perform(get("/api/plugin-catalog")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/assets/backgrounds/00000000-0000-0000-0000-000000000000.png"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/artifacts/0123456789abcdef0123456789abcdef"))
                .andExpect(status().isUnauthorized());
        // 未登录写请求先被 CSRF 过滤器拒绝
        mvc.perform(post("/api/instances/i-000000000000/plugins")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spec\":\"left-pad\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    @Order(2)
    void pluginListRequiresExistingInstanceAndValidProfile() throws Exception {
        MvcResult created = mvc.perform(write(post("/api/instances"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "插件实例", "version", "0.1.0-rc.6"))))
                .andExpect(status().isCreated())
                .andReturn();
        instanceId = json.readTree(created.getResponse().getContentAsString()).path("id").asText();

        mvc.perform(read(get("/api/instances/" + instanceId + "/plugins")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(read(get("/api/instances/" + instanceId + "/plugins").param("profile", "web")))
                .andExpect(status().isOk());
        mvc.perform(read(get("/api/instances/i-000000000000/plugins")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(read(get("/api/instances/" + instanceId + "/plugins").param("profile", "../evil")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        mvc.perform(get("/api/instances/" + instanceId + "/plugins"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(3)
    void pluginMutationValidatesSynchronouslyAndJobFailsCleanly() throws Exception {
        // 空 spec → 400
        mvc.perform(write(post("/api/instances/" + instanceId + "/plugins"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        // 实例不存在 → 404
        mvc.perform(write(post("/api/instances/i-000000000000/plugins"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spec\":\"left-pad\"}"))
                .andExpect(status().isNotFound());
        // 实例运行中 → 409（变更插件要求实例已停止）
        startFixtureProcess(instanceId);
        try {
            mvc.perform(write(post("/api/instances/" + instanceId + "/plugins"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"spec\":\"left-pad\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT_INSTANCE_RUNNING"));
        } finally {
            processes.stop(instanceId, line -> { });
        }
        // 忙时 → 409
        CountDownLatch release = new CountDownLatch(1);
        jobs.submit("GATE_TEST", Duration.ofSeconds(30), context -> {
            context.checkCancelled();
            release.await();
            return Map.of("ok", true);
        });
        try {
            mvc.perform(write(post("/api/instances/" + instanceId + "/plugins"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"spec\":\"left-pad\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT_BUSY"));
        } finally {
            release.countDown();
            awaitIdle();
        }

        // 合法 spec → 202 Job；运行时未安装 → 任务快速失败（不执行 pnpm/网络）
        MvcResult accepted = mvc.perform(write(post("/api/instances/" + instanceId + "/plugins"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spec\":\"left-pad\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(containsString("j-")))
                .andExpect(jsonPath("$.type").value("PLUGIN_MUTATE"))
                .andReturn();
        String jobId = json.readTree(accepted.getResponse().getContentAsString()).path("id").asText();
        JobManager.View failed = awaitTerminal(jobId);
        assertEquals(JobManager.State.FAILED, failed.state());
        assertTrue(failed.error() != null && failed.error().message().contains("尚未安装"),
                "运行时未安装时任务应给出中文失败原因：" + failed.error());

        // 移除（DELETE，含 scoped 包名）同样走 Job：同步校验通过后因运行时未安装失败
        MvcResult removing = mvc.perform(write(delete("/api/instances/" + instanceId + "/plugins/@scope/left-pad")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("PLUGIN_MUTATE"))
                .andReturn();
        String removeJob = json.readTree(removing.getResponse().getContentAsString()).path("id").asText();
        assertEquals(JobManager.State.FAILED, awaitTerminal(removeJob).state());
        awaitIdle();
    }

    @Test
    @Order(4)
    void pluginCatalogValidatesInputAndMapsUpstreamErrors() throws Exception {
        // 代理指向本机不可达端口：目录请求快速失败 → 中文 502
        mvc.perform(write(put("/api/settings"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("proxy", "http://127.0.0.1:1", "registry", DEFAULT_REGISTRY, "background", ""))))
                .andExpect(status().isOk());
        try {
            MvcResult search = mvc.perform(read(get("/api/plugin-catalog").param("q", "test")))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("UPSTREAM_ERROR"))
                    .andReturn();
            String message = json.readTree(search.getResponse().getContentAsString()).path("message").asText();
            assertTrue(message.matches(".*[\\u4e00-\\u9fff].*"), "上游错误提示应为中文：" + message);
            mvc.perform(read(get("/api/plugin-catalog/some-owner/some-repo")))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("UPSTREAM_ERROR"));
        } finally {
            mvc.perform(write(put("/api/settings"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("proxy", "", "registry", DEFAULT_REGISTRY, "background", ""))))
                    .andExpect(status().isOk());
        }
        // 输入校验在发起网络请求之前完成
        mvc.perform(read(get("/api/plugin-catalog").param("q", "<script>alert(1)</script>")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        mvc.perform(read(get("/api/plugin-catalog").param("page", "0")))
                .andExpect(status().isBadRequest());
        mvc.perform(read(get("/api/plugin-catalog").param("page", "1000000")))
                .andExpect(status().isBadRequest());
        mvc.perform(read(get("/api/plugin-catalog/bad_owner/some-repo")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    @Order(5)
    void backgroundUploadAndServingAreValidated() throws Exception {
        pngBytes = pngBytes(1, 1);
        MockMultipartFile image = new MockMultipartFile("file", "background.png", "image/png", pngBytes);
        MvcResult uploaded = mvc.perform(write(multipart("/api/settings/background").file(image)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.background").value(containsString("/api/assets/backgrounds/")))
                .andReturn();
        backgroundRef = json.readTree(uploaded.getResponse().getContentAsString()).path("background").asText();
        assertTrue(backgroundRef.matches("/api/assets/backgrounds/[a-f0-9-]{36}\\.png"),
                "背景资源地址格式不正确：" + backgroundRef);

        // 同源读取（登录会话）返回原始字节
        mvc.perform(read(get(backgroundRef)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(pngBytes));

        // 非法内容 / 超大文件 / 缺失文件 / 非法名称
        MockMultipartFile text = new MockMultipartFile("file", "evil.png", "image/png",
                "not-an-image".getBytes(StandardCharsets.UTF_8));
        mvc.perform(write(multipart("/api/settings/background").file(text)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("PNG 或 JPEG")));
        MockMultipartFile oversized = new MockMultipartFile("file", "big.png", "image/png",
                new byte[20 * 1024 * 1024 + 1]);
        mvc.perform(write(multipart("/api/settings/background").file(oversized)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("20 MB")));
        mvc.perform(write(multipart("/api/settings/background")))
                .andExpect(status().isBadRequest());
        mvc.perform(read(get("/api/assets/backgrounds/bad.txt")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        // 路径穿越尝试必须被拒绝（4xx，不返回文件）
        mvc.perform(read(get("/api/assets/backgrounds/..%2F..%2Fconfig%2Fserver.json")))
                .andExpect(status().is4xxClientError());
        mvc.perform(read(get("/api/assets/backgrounds/00000000-0000-0000-0000-000000000000.png")))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(6)
    void settingsBackgroundReferenceIsValidated() throws Exception {
        mvc.perform(write(put("/api/settings"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("proxy", "", "registry", DEFAULT_REGISTRY, "background", "builtin:whale-01"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.background").value("builtin:whale-01"));
        mvc.perform(write(put("/api/settings"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("proxy", "", "registry", DEFAULT_REGISTRY, "background", backgroundRef))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.background").value(backgroundRef));
        for (String invalid : List.of("builtin:whale-06", "builtin:whale-00", "/api/assets/backgrounds/../escape.png",
                "http://example.com/x.png", "whale-01", "/api/assets/backgrounds/x.exe")) {
            mvc.perform(write(put("/api/settings"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("proxy", "", "registry", DEFAULT_REGISTRY, "background", invalid))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION"));
        }
        mvc.perform(write(put("/api/settings"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("proxy", "", "registry", DEFAULT_REGISTRY, "background", ""))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.background").value(""));
    }

    @Test
    @Order(7)
    void artifactRegisterAndDownload() throws Exception {
        Path exports = data.cache().resolve("exports");
        Files.createDirectories(exports);
        byte[] content = "dspack-test-content".getBytes(StandardCharsets.UTF_8);
        Path file = exports.resolve("sample.dspack");
        Files.write(file, content);
        String id = artifacts.register(file, "测试包.dspack", "application/octet-stream");
        assertTrue(id.matches("[a-f0-9]{32}"));

        mvc.perform(read(get("/api/artifacts/" + id)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(content))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Cache-Control", containsString("no-store")));

        // 未知 / 过期（未登记）/ 非法 id → 404
        mvc.perform(read(get("/api/artifacts/0123456789abcdef0123456789abcdef")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(read(get("/api/artifacts/not-valid")))
                .andExpect(status().isNotFound());

        // 只允许登记 data/cache/exports 内的文件
        Path outside = data.cache().resolve("outside.bin");
        Files.write(outside, content);
        assertThrows(IllegalArgumentException.class,
                () -> artifacts.register(outside, "outside.bin", "application/octet-stream"));
        assertThrows(IllegalArgumentException.class,
                () -> artifacts.register(exports.resolve("missing.bin"), "missing.bin", "application/octet-stream"));
    }

    // ------------------------------------------------------------------ 辅助

    private void startFixtureProcess(String id) throws Exception {
        Instance instance = store.find(id);
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        Path testClasses = Path.of(SleepMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> command = List.of(java.toString(), "-cp", testClasses.toString(), SleepMain.class.getName());
        java.util.Map<String, String> environment = new HashMap<>(System.getenv());
        processes.start(instance, command, store.workspace(instance), environment, line -> { });
    }

    private JobManager.View awaitTerminal(String id) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000L;
        JobManager.View view = jobs.view(id);
        while (view.state() == JobManager.State.RUNNING && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
            view = jobs.view(id);
        }
        assertNotEquals(JobManager.State.RUNNING, view.state(), "任务未在预期时间内结束");
        return view;
    }

    private static byte[] pngBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFF5C6BC0);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private void awaitIdle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (jobs.busy() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertFalse(jobs.busy(), "测试开始前应处于空闲状态");
    }
}
