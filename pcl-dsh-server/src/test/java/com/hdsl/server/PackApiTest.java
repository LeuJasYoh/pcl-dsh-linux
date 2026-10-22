package com.hdsl.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.hdsl.core.Instance;
import com.hdsl.core.InstanceStore;
import com.hdsl.core.ProcessService;
import com.hdsl.pack.PackInspection;
import com.hdsl.pack.PackService;
import com.hdsl.server.api.ApiException;
import com.hdsl.server.config.DataRoot;
import com.hdsl.server.job.JobManager;
import com.hdsl.server.pack.PackApiService;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 整合包/复制/改版本集成测试（不依赖 Node/网络）：
 * <ul>
 *   <li>合成最小 .dspack（dspack.json v3 + manifest.json v5 type=profile）验证预览、坏包、换包；</li>
 *   <li>临时 dsh-home 写 profile/package.json 验证 export→inspect 往返与 artifact 下载；</li>
 *   <li>空白 web 实例复制、PUT 改版本返回 202 Job、profile 未初始化 400；</li>
 *   <li>真实导入依赖 pnpm/Node 与网络，不在自动化范围（仅覆盖前置校验与 Job 提交边界）。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackApiTest extends ApiTestSupport {

    private static final Path DATA_ROOT = TestDataRoots.prepare("pack-api");

    private static String previewId;
    private static String exportInstanceId;

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

    @BeforeEach
    void setUp() throws Exception {
        authenticate(DATA_ROOT);
        awaitIdle();
    }

    @Test
    @Order(1)
    void previewRejectsInvalidUploadsAndCleansUp() throws Exception {
        // 未登录写请求先被 CSRF 拦截（鉴权分支）
        mvc.perform(post("/api/packs/import").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previewId\":\"p-x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        // 非法 ZIP → 400 VALIDATION，且不残留上传文件
        MockMultipartFile broken = new MockMultipartFile("file", "broken.zip", "application/zip",
                "not-a-zip".getBytes(StandardCharsets.UTF_8));
        mvc.perform(write(multipart("/api/packs/preview").file(broken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        assertEquals(0, uploadFileCount(), "非法上传不得残留文件");
        // 缺少格式标记的 ZIP → 400
        MockMultipartFile noMarker = new MockMultipartFile("file", "no-marker.zip", "application/zip",
                zipOf(Map.of("random.txt", "hello")));
        mvc.perform(write(multipart("/api/packs/preview").file(noMarker)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        assertEquals(0, uploadFileCount());
        // 缺少 file 字段 → 400
        mvc.perform(write(multipart("/api/packs/preview")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(2)
    void previewReturnsDesktopDescribeFields() throws Exception {
        MockMultipartFile pack = new MockMultipartFile("file", "test-pack.dspack", "application/zip",
                minimalProfilePack("test-pack", "1.0.0", "0.1.0-rc.6"));
        MvcResult result = mvc.perform(write(multipart("/api/packs/preview").file(pack)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewId").value(containsString("p-")))
                .andExpect(jsonPath("$.name").value("test-pack"))
                .andExpect(jsonPath("$.format").value("dspack"))
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.dshVersion").value("0.1.0-rc.6"))
                .andExpect(jsonPath("$.profiles[0]").value("web"))
                .andExpect(jsonPath("$.unpackedBytes").isNumber())
                .andExpect(jsonPath("$.warnings").isArray())
                .andReturn();
        previewId = json.readTree(result.getResponse().getContentAsString()).path("previewId").asText();
        assertEquals(1, uploadFileCount(), "成功预览应保留 1 个上传文件");
    }

    @Test
    @Order(3)
    void importValidatesPreviewVersionAndDetectsSwappedArchive() throws Exception {
        // previewId 不存在/过期 → 404
        mvc.perform(write(post("/api/packs/import"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("previewId", "p-does-not-exist"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(containsString("预览")));
        // 请求版本格式非法 → 400
        mvc.perform(write(post("/api/packs/import"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("previewId", previewId, "version", "not-a-version"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        // 换包检测：预览后替换上传文件内容 → 400 中文提示
        Path upload = firstUploadFile();
        Files.write(upload, minimalProfilePack("swapped-pack", "2.0.0", "0.1.0-rc.6"));
        mvc.perform(write(post("/api/packs/import"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("previewId", previewId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.message").value(containsString("变化")));
    }

    @Test
    @Order(4)
    void exportProfileRoundTripAndArtifactDownload() throws Exception {
        MvcResult created = mvc.perform(write(post("/api/instances"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "导出实例", "version", "0.1.0-rc.6"))))
                .andExpect(status().isCreated())
                .andReturn();
        exportInstanceId = json.readTree(created.getResponse().getContentAsString()).path("id").asText();
        Instance instance = store.find(exportInstanceId);
        Path profile = store.home(instance).resolve("profiles/web");
        Files.createDirectories(profile);
        Files.writeString(profile.resolve("package.json"),
                "{\"name\":\"dsh-profile-web\",\"private\":true,\"dependencies\":{},\"dsh\":{\"profile\":{\"bundles\":[]}}}");

        // 导出 profile → 202 EXPORT_PACK → 成功 result {artifactId,filename,size}
        MvcResult accepted = mvc.perform(write(post("/api/packs/export"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("instanceId", exportInstanceId, "mode", "profile"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("EXPORT_PACK"))
                .andReturn();
        String jobId = json.readTree(accepted.getResponse().getContentAsString()).path("id").asText();
        JsonNode result = awaitJobResult(jobId);
        String artifactId = result.path("artifactId").asText();
        String filename = result.path("filename").asText();
        long size = result.path("size").asLong();
        assertTrue(artifactId.matches("[a-f0-9]{32}"));
        assertTrue(filename.endsWith(".dspack"), "导出文件名应以 .dspack 结尾：" + filename);
        assertTrue(size > 0);

        // 下载并重新 inspect，验证 export → inspect 往返
        MvcResult download = mvc.perform(read(get("/api/artifacts/" + artifactId)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andReturn();
        byte[] archive = download.getResponse().getContentAsByteArray();
        assertEquals(size, archive.length);
        Path temp = Files.createTempFile(DATA_ROOT, "roundtrip-", ".dspack");
        try {
            Files.write(temp, archive);
            PackInspection inspection = new PackService().inspect(temp);
            assertEquals("dspack", inspection.format());
            assertEquals("profile", inspection.manifest().path("type").asText());
            assertEquals("1.0.0", inspection.version());
            assertEquals("0.1.0-rc.6", inspection.dshVersion());
            assertTrue(inspection.profiles().contains("web"));
        } finally {
            Files.deleteIfExists(temp);
        }

        // mode 非法 / 实例不存在 / 运行中
        mvc.perform(write(post("/api/packs/export"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("instanceId", exportInstanceId, "mode", "everything"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        mvc.perform(write(post("/api/packs/export"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("instanceId", "i-000000000000", "mode", "profile"))))
                .andExpect(status().isNotFound());
        startFixtureProcess(exportInstanceId);
        try {
            mvc.perform(write(post("/api/packs/export"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("instanceId", exportInstanceId, "mode", "profile"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT_INSTANCE_RUNNING"));
        } finally {
            processes.stop(exportInstanceId, line -> { });
        }
    }

    @Test
    @Order(5)
    void copyBlankWebInstanceCreatesIndependentInstance() throws Exception {
        MvcResult created = mvc.perform(write(post("/api/instances"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "空白源实例", "version", "0.1.0-rc.6"))))
                .andExpect(status().isCreated())
                .andReturn();
        String sourceId = json.readTree(created.getResponse().getContentAsString()).path("id").asText();

        MvcResult accepted = mvc.perform(write(post("/api/instances/" + sourceId + "/copy"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "副本实例"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("COPY_INSTANCE"))
                .andReturn();
        String jobId = json.readTree(accepted.getResponse().getContentAsString()).path("id").asText();
        JsonNode result = awaitJobResult(jobId);
        String copyId = result.path("instanceId").asText();
        assertFalse(copyId.equals(sourceId), "复制必须产生新实例");
        assertEquals("副本实例", result.path("name").asText());
        mvc.perform(read(get("/api/instances/" + copyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("副本实例"))
                .andExpect(jsonPath("$.version").value("0.1.0-rc.6"))
                .andExpect(jsonPath("$.profile").value("web"));
        // 原实例保留
        mvc.perform(read(get("/api/instances/" + sourceId))).andExpect(status().isOk());

        // 校验分支：未知实例 404、名称为空 400
        mvc.perform(write(post("/api/instances/i-000000000000/copy"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "副本"))))
                .andExpect(status().isNotFound());
        mvc.perform(write(post("/api/instances/" + sourceId + "/copy"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    @Order(6)
    void putVersionChangeReturnsCopyJobAndProfileMustExist() throws Exception {
        MvcResult created = mvc.perform(write(post("/api/instances"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "改版源实例", "version", "0.1.0-rc.6"))))
                .andExpect(status().isCreated())
                .andReturn();
        String sourceId = json.readTree(created.getResponse().getContentAsString()).path("id").asText();

        MvcResult accepted = mvc.perform(write(put("/api/instances/" + sourceId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "改版新实例", "version", "0.1.0-rc.7"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("COPY_INSTANCE"))
                .andReturn();
        String jobId = json.readTree(accepted.getResponse().getContentAsString()).path("id").asText();
        JsonNode result = awaitJobResult(jobId);
        String changedId = result.path("instanceId").asText();
        assertEquals("改版新实例", result.path("name").asText());
        mvc.perform(read(get("/api/instances/" + changedId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("0.1.0-rc.7"));
        // 原实例版本不变
        mvc.perform(read(get("/api/instances/" + sourceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("0.1.0-rc.6"));
        // 目标 profile 未初始化 → 400
        mvc.perform(write(put("/api/instances/" + sourceId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "改版源实例", "profile", "custom"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.message").value(containsString("尚未初始化")));
        // version/profile 未变 → 普通同步修改 200
        mvc.perform(write(put("/api/instances/" + sourceId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "改版源实例2", "version", "0.1.0-rc.6", "profile", "web"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("改版源实例2"));
    }

    @Test
    @Order(7)
    void exportHomeModeProducesDshhomePack() throws Exception {
        MvcResult created = mvc.perform(write(post("/api/instances"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "home导出实例", "version", "0.1.0-rc.6"))))
                .andExpect(status().isCreated())
                .andReturn();
        String id = json.readTree(created.getResponse().getContentAsString()).path("id").asText();
        Instance instance = store.find(id);
        Path profile = store.home(instance).resolve("profiles/custom");
        Files.createDirectories(profile);
        Files.writeString(profile.resolve("package.json"),
                "{\"name\":\"dsh-profile-custom\",\"private\":true,\"dependencies\":{},\"dsh\":{\"profile\":{\"bundles\":[]}}}");
        store.replace(new Instance(instance.id(), instance.name(), instance.runtimeVersion(), "custom",
                instance.port(), instance.customCommand(), instance.accountId()));

        MvcResult accepted = mvc.perform(write(post("/api/packs/export"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("instanceId", id, "mode", "home"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("EXPORT_PACK"))
                .andReturn();
        String jobId = json.readTree(accepted.getResponse().getContentAsString()).path("id").asText();
        JsonNode result = awaitJobResult(jobId);
        MvcResult download = mvc.perform(read(get("/api/artifacts/" + result.path("artifactId").asText())))
                .andExpect(status().isOk())
                .andReturn();
        Path temp = Files.createTempFile(DATA_ROOT, "home-roundtrip-", ".dspack");
        try {
            Files.write(temp, download.getResponse().getContentAsByteArray());
            PackInspection inspection = new PackService().inspect(temp);
            assertEquals("dspack", inspection.format());
            assertEquals("dshhome", inspection.manifest().path("type").asText());
            assertEquals("custom", inspection.defaultProfile());
            assertTrue(inspection.profiles().contains("custom"));
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Test
    @Order(8)
    void oversizedUploadIsRejectedBeforeStreaming() {
        PackApiService service = new PackApiService(null, null, null, null, jobs, null, null, null, null);
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(2L * 1024 * 1024 * 1024 + 1);
        ApiException failure = assertThrows(ApiException.class, () -> service.preview(file, "127.0.0.1"));
        assertEquals("PAYLOAD_TOO_LARGE", failure.code());
        assertEquals(413, failure.status().value());
    }

    // ------------------------------------------------------------------ 辅助

    /** 最小合法整合包：dspack 容器 v3 + manifest v5 type=profile（无依赖、无额外文件）。 */
    private static byte[] minimalProfilePack(String name, String version, String dshVersion) throws Exception {
        String manifest = "{\"manifestVersion\":5,\"type\":\"profile\",\"name\":\"" + name
                + "\",\"version\":\"" + version + "\",\"dshVersion\":\"" + dshVersion
                + "\",\"profileName\":\"web\",\"bundles\":[],\"dependencies\":{}}";
        return zipOf(Map.of(
                "dspack.json", "{\"format\":\"dspack\",\"version\":3}",
                "manifest.json", manifest));
    }

    private static byte[] zipOf(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private long uploadFileCount() throws Exception {
        Path uploads = data.cache().resolve("uploads");
        if (!Files.isDirectory(uploads)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(uploads)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }

    private Path firstUploadFile() throws Exception {
        try (Stream<Path> stream = Files.list(data.cache().resolve("uploads"))) {
            return stream.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
    }

    private void startFixtureProcess(String id) throws Exception {
        Instance instance = store.find(id);
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        Path testClasses = Path.of(SleepMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> command = List.of(java.toString(), "-cp", testClasses.toString(), SleepMain.class.getName());
        Map<String, String> environment = new HashMap<>(System.getenv());
        processes.start(instance, command, store.workspace(instance), environment, line -> { });
    }

    private JsonNode awaitJobResult(String jobId) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000L;
        JobManager.View view = jobs.view(jobId);
        while (view.state() == JobManager.State.RUNNING && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
            view = jobs.view(jobId);
        }
        assertEquals(JobManager.State.SUCCEEDED, view.state(), "任务应成功：" + view.error());
        return json.valueToTree(view.result());
    }

    private void awaitIdle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (jobs.busy() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertFalse(jobs.busy(), "测试开始前应处于空闲状态");
    }
}
