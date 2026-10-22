package com.hdsl.server.pack;

import com.hdsl.core.Instance;
import com.hdsl.core.InstanceStore;
import com.hdsl.pack.PackInspection;
import com.hdsl.pack.PackService;
import com.hdsl.server.api.ApiDtos;
import com.hdsl.server.api.ApiException;
import com.hdsl.server.artifact.ArtifactStore;
import com.hdsl.server.config.DataRoot;
import com.hdsl.server.config.RuntimeProvider;
import com.hdsl.server.instance.InstanceService;
import com.hdsl.server.job.JobManager;
import com.hdsl.server.log.LogFiles;
import com.hdsl.server.security.AuditLog;
import com.hdsl.server.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 整合包接口业务（方案 §4.2 / §4.7）：上传预览、导入 Job、导出 Job。
 *
 * <ul>
 *   <li>上传流式落盘到 data/cache/uploads（2 GiB 上限）并同时计算 SHA-256，不整体读入内存；</li>
 *   <li>预览 30 分钟 TTL；导入前重新核对 SHA-256 防换包；导入时用 previewId 避免重复上传；</li>
 *   <li>导入/导出统一走 JobManager（IMPORT_PACK 30 分钟、EXPORT_PACK 10 分钟），失败清理临时文件；</li>
 *   <li>导出产物登记到 ArtifactStore，由 GET /api/artifacts/{id} 下载。</li>
 * </ul>
 */
@Component
public class PackApiService {

    private static final Logger log = LoggerFactory.getLogger(PackApiService.class);
    private static final long MAX_UPLOAD_BYTES = 2L * 1024 * 1024 * 1024;
    private static final Duration IMPORT_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration EXPORT_TIMEOUT = Duration.ofMinutes(10);

    private final DataRoot data;
    private final InstanceStore store;
    private final InstanceService instances;
    private final RuntimeProvider runtimes;
    private final JobManager jobs;
    private final AuditLog audit;
    private final LogFiles logs;
    private final PreviewStore previews;
    private final ArtifactStore artifacts;

    public PackApiService(DataRoot data, InstanceStore store, InstanceService instances, RuntimeProvider runtimes,
                          JobManager jobs, AuditLog audit, LogFiles logs, PreviewStore previews,
                          ArtifactStore artifacts) {
        this.data = data;
        this.store = store;
        this.instances = instances;
        this.runtimes = runtimes;
        this.jobs = jobs;
        this.audit = audit;
        this.logs = logs;
        this.previews = previews;
        this.artifacts = artifacts;
    }

    // ------------------------------------------------------------------ 预览

    /** POST /api/packs/preview：流式上传 + PackService.inspect，返回 30 分钟有效的 previewId。 */
    public ApiDtos.PackPreviewDto preview(MultipartFile file, String ip) {
        jobs.ensureIdle();
        if (file == null || file.isEmpty()) {
            throw ApiException.validation("请选择要上传的整合包文件。");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new ApiException("PAYLOAD_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE, "整合包不能超过 2 GiB。");
        }
        Path upload;
        try {
            upload = previews.allocate();
        } catch (IOException failure) {
            log.warn("上传目录不可用：{}", failure.toString());
            throw ApiException.internal("上传目录不可用，请检查数据目录权限。");
        }
        String sha;
        long size = 0;
        try (InputStream input = file.getInputStream();
             DigestOutputStream output = new DigestOutputStream(
                     Files.newOutputStream(upload, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                     MessageDigest.getInstance("SHA-256"))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                size += read;
                if (size > MAX_UPLOAD_BYTES) {
                    throw new ApiException("PAYLOAD_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE, "整合包不能超过 2 GiB。");
                }
                output.write(buffer, 0, read);
            }
            sha = HexFormat.of().formatHex(output.getMessageDigest().digest());
        } catch (ApiException failure) {
            deleteQuietly(upload);
            throw failure;
        } catch (IOException | NoSuchAlgorithmException failure) {
            deleteQuietly(upload);
            log.warn("保存上传整合包失败：{}", failure.toString());
            throw ApiException.internal("上传整合包失败，请重试。");
        }
        if (size == 0) {
            deleteQuietly(upload);
            throw ApiException.validation("整合包文件为空。");
        }
        PackInspection inspection;
        try {
            inspection = packs().inspect(upload);
        } catch (IOException failure) {
            deleteQuietly(upload);
            throw ApiException.validation(LogSanitizer.oneLine(failure.getMessage(), 300));
        } catch (Exception failure) {
            deleteQuietly(upload);
            log.warn("检查整合包失败：{}", failure.toString());
            throw ApiException.validation("无法识别整合包；请确认文件为 .dspack 或同平台快照 ZIP。");
        }
        if (!sha.equalsIgnoreCase(inspection.archiveSha256())) {
            deleteQuietly(upload);
            throw ApiException.internal("整合包校验失败，请重新上传。");
        }
        PreviewStore.Preview preview = previews.register(upload, sha, inspection, size);
        audit.record("PACK_PREVIEWED", ip, "name=" + LogSanitizer.oneLine(inspection.name(), 64)
                + " format=" + inspection.format() + " size=" + size);
        logs.append("已上传整合包预览：" + inspection.name() + "（" + inspection.format() + "，"
                + size + " 字节）");
        return new ApiDtos.PackPreviewDto(preview.id(), inspection.name(), inspection.format(),
                inspection.version(), inspection.dshVersion(), inspection.profiles(),
                inspection.unpackedBytes(), inspection.warnings());
    }

    // ------------------------------------------------------------------ 导入

    /** POST /api/packs/import：同步校验后提交 IMPORT_PACK 任务。 */
    public JobManager.View importPack(ApiDtos.PackImportRequest request, String ip) {
        jobs.ensureIdle();
        if (request == null) {
            throw ApiException.badRequest("请求体不能为空。");
        }
        PreviewStore.Preview preview = requirePreview(request.previewId());
        String name = trim(request.name());
        if (!name.isEmpty()) {
            validateName(name);
        }
        String requestedVersion = trim(request.version());
        if (!requestedVersion.isEmpty()) {
            validateVersion(requestedVersion);
        }
        String version = requestedVersion.isEmpty()
                ? resolveVersion(preview.inspection(), installedVersions()) : requestedVersion;
        if (version.isBlank()) {
            throw ApiException.validation("包未声明 DSH 版本，请在导入框填写一个精确版本号。");
        }
        verifyUnchanged(preview);
        audit.record("PACK_IMPORT_REQUESTED", ip, "name="
                + LogSanitizer.oneLine(name.isEmpty() ? preview.inspection().name() : name, 64)
                + " version=" + version);
        logs.append("请求导入整合包：" + preview.inspection().name() + " → Harness " + version);
        return jobs.submit("IMPORT_PACK", IMPORT_TIMEOUT, context -> {
            PreviewStore.Preview current = requirePreview(preview.id());
            verifyUnchanged(current);
            context.checkCancelled();
            ApiDtos.PackImportResultDto result = instances.importInspection(context, current.inspection(),
                    name, version, "", ip);
            previews.remove(current.id());
            return result;
        });
    }

    // ------------------------------------------------------------------ 导出

    /** POST /api/packs/export：同步校验后提交 EXPORT_PACK 任务，产物登记为 artifact。 */
    public JobManager.View exportPack(ApiDtos.PackExportRequest request, String ip) {
        jobs.ensureIdle();
        if (request == null) {
            throw ApiException.badRequest("请求体不能为空。");
        }
        String instanceId = trim(request.instanceId());
        String mode = trim(request.mode()).toLowerCase(Locale.ROOT);
        if (!Set.of("profile", "home").contains(mode)) {
            throw ApiException.validation("导出范围必须是 profile 或 home。");
        }
        Instance instance = instances.requireInstance(instanceId);
        instances.ensureStoppedForMutation(instance);
        audit.record("PACK_EXPORT_REQUESTED", ip, "instance=" + instanceId + " mode=" + mode);
        logs.append("请求导出整合包：" + instance.name() + "（" + mode + "）");
        return jobs.submit("EXPORT_PACK", EXPORT_TIMEOUT, context -> {
            Instance current = instances.requireInstance(instanceId);
            instances.ensureStoppedForMutation(current);
            context.phase("导出整合包");
            Path exports = data.cache().resolve("exports");
            Files.createDirectories(exports);
            String filename = packName(current) + ".dspack";
            Path output = exports.resolve(UUID.randomUUID().toString().replace("-", "") + ".dspack");
            try {
                PackService packs = packs();
                if ("home".equals(mode)) {
                    packs.exportHome(store.home(current), current.profile(), current.runtimeVersion(),
                            packName(current), "1.0.0", output, context::log);
                } else {
                    packs.exportProfile(store.home(current), current.profile(), current.runtimeVersion(),
                            packName(current), "1.0.0", output, context::log);
                }
                context.checkCancelled();
                long size = Files.size(output);
                String artifactId = artifacts.register(output, filename, "application/zip");
                context.log("导出完成：" + filename + "（" + size + " 字节）。");
                logs.append("已导出整合包：" + filename + "（实例 " + instanceId + "）");
                return new ApiDtos.PackExportResultDto(artifactId, filename, size);
            } catch (Exception failure) {
                deleteQuietly(output);
                throw failure;
            }
        });
    }

    // ------------------------------------------------------------------ 内部工具

    private PreviewStore.Preview requirePreview(String previewId) {
        PreviewStore.Preview preview = previews.find(trim(previewId));
        if (preview == null) {
            throw ApiException.notFound("整合包预览不存在或已过期（保留 30 分钟），请重新上传。");
        }
        return preview;
    }

    /** 导入前核对上传文件 SHA-256：防止预览后被替换（换包攻击）。 */
    private void verifyUnchanged(PreviewStore.Preview preview) {
        String current;
        try {
            current = sha256(preview.archive());
        } catch (IOException failure) {
            throw ApiException.internal("读取上传整合包失败，请重新上传。");
        }
        if (!current.equalsIgnoreCase(preview.sha256())) {
            throw ApiException.validation("上传的整合包文件已变化，请重新上传预览。");
        }
    }

    /** 版本解析（与桌面版 resolvePackVersion 一致）：请求版本 > 包 dshVersion > 已装兼容 > 已装最新。 */
    private static String resolveVersion(PackInspection inspection, List<String> installed) {
        String preferred = inspection.dshVersion() == null ? "" : inspection.dshVersion();
        if (installed.contains(preferred)) {
            return preferred;
        }
        Set<String> compatible = new LinkedHashSet<>();
        inspection.manifest().path("dshVersions").forEach(node -> {
            if (node.isTextual()) {
                compatible.add(node.asText());
            }
        });
        for (String candidate : installed) {
            if (compatible.contains(candidate)) {
                return candidate;
            }
        }
        if (!preferred.isBlank()) {
            return preferred;
        }
        if (!compatible.isEmpty()) {
            return compatible.iterator().next();
        }
        return installed.isEmpty() ? "" : installed.getFirst();
    }

    private List<String> installedVersions() {
        try {
            return runtimes.get().installedVersions();
        } catch (IOException failure) {
            throw ApiException.internal("读取已安装运行时失败，请稍后重试。");
        } catch (RuntimeException failure) {
            throw ApiException.internal("运行时设置无效，请检查“设置”页面。");
        }
    }

    private PackService packs() {
        String proxy = store.setting("proxy", "");
        try {
            return proxy == null || proxy.isBlank() ? new PackService() : new PackService(URI.create(proxy.trim()));
        } catch (IllegalArgumentException failure) {
            throw ApiException.internal("代理设置无效，无法处理整合包，请检查“设置”页面。");
        }
    }

    /** 与桌面版 packName 一致：小写 kebab-case 保留，否则回退 hdsl-<id>。 */
    private static String packName(Instance instance) {
        if (instance.name().matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            return instance.name();
        }
        String id = instance.id().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return "hdsl-" + id;
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Java SHA-256 不可用", failure);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 160) {
            throw ApiException.validation("实例名称须为 1–160 个字符。");
        }
    }

    private static void validateVersion(String version) {
        try {
            InstanceStore.validateVersion(version);
        } catch (IOException failure) {
            throw ApiException.validation(failure.getMessage());
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 清理尽力而为。
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
