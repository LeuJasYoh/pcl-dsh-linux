package com.hdsl.server.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hdsl.core.InstanceStore;
import com.hdsl.server.config.DataRoot;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 导出产物登记与下载基础设施（方案 §4.7，供整合包导出使用）。
 *
 * <ul>
 *   <li>只登记 data/cache/exports 内的普通文件，避免任意文件下载；</li>
 *   <li>元数据持久化到 {@code data/cache/exports/.artifacts.json}（原子写），服务重启后旧链接仍可下载；</li>
 *   <li>TTL 24 小时：启动时 + 定时清理过期登记（删除文件）与文件缺失的登记；</li>
 *   <li>所有读写/清理在同一把锁内完成，登记、下载、清理并发安全；</li>
 *   <li>id 为随机 32 位十六进制，不可枚举；文件名下载时做安全化。</li>
 * </ul>
 */
@Component
public class ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStore.class);
    static final String METADATA_FILE = ".artifacts.json";
    private static final int METADATA_SCHEMA_VERSION = 1;
    private static final long MAX_METADATA_BYTES = 4L * 1024 * 1024;
    public static final java.time.Duration TTL = java.time.Duration.ofHours(24);
    private static final Pattern ID = Pattern.compile("[a-f0-9]{32}");
    private static final Pattern CONTENT_TYPE =
            Pattern.compile("[a-z0-9][a-z0-9.+-]{0,63}/[a-z0-9][a-z0-9.+-]{0,63}");
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataRoot data;
    private final Object lock = new Object();
    private final Map<String, Artifact> artifacts = new ConcurrentHashMap<>();

    public ArtifactStore(DataRoot data) {
        this.data = data;
    }

    public record Artifact(String id, Path path, String filename, String contentType, long size,
                           long createdAt, long expiresAt) {
    }

    /** 启动时加载持久化元数据并清理过期/缺失的登记。 */
    @PostConstruct
    public void initialize() {
        synchronized (lock) {
            try {
                Files.createDirectories(exportsDirectory());
            } catch (IOException failure) {
                log.warn("产物目录不可用：{}", failure.toString());
            }
            loadLocked();
            // 启动时总是回写一次：清理过期/缺失/非法记录并规范化元数据。
            sweepLocked();
            persistQuietlyLocked();
            if (!artifacts.isEmpty()) {
                log.info("已加载 {} 个导出产物登记（TTL 24 小时）。", artifacts.size());
            }
        }
    }

    /**
     * 登记导出目录内的文件并立即持久化元数据。
     *
     * @return 下载 id（32 位十六进制）
     * @throws IllegalArgumentException 文件不在 data/cache/exports 内、不存在或不可读
     */
    public String register(Path file, String filename, String contentType) {
        synchronized (lock) {
            sweepLocked();
            Path exports = exportsDirectory();
            if (file == null) {
                throw new IllegalArgumentException("产物文件不能为空。");
            }
            Path normalized = file.toAbsolutePath().normalize();
            if (!normalized.startsWith(exports) || normalized.equals(exports)) {
                throw new IllegalArgumentException("产物必须位于 data/cache/exports 内。");
            }
            if (!Files.isRegularFile(normalized)) {
                throw new IllegalArgumentException("产物文件不存在。");
            }
            long size;
            try {
                size = Files.size(normalized);
            } catch (IOException failure) {
                throw new IllegalArgumentException("产物文件不可读。", failure);
            }
            String safeName = safeFilename(filename, normalized.getFileName().toString());
            String type = contentType == null || contentType.isBlank()
                    ? DEFAULT_CONTENT_TYPE : contentType.trim().toLowerCase(Locale.ROOT);
            if (!CONTENT_TYPE.matcher(type).matches()) {
                type = DEFAULT_CONTENT_TYPE;
            }
            long now = System.currentTimeMillis();
            String id = UUID.randomUUID().toString().replace("-", "");
            artifacts.put(id, new Artifact(id, normalized, safeName, type, size, now,
                    now + TTL.toMillis()));
            persistLocked();
            return id;
        }
    }

    /** 查询未过期且仍位于导出目录内的产物；未知/过期/文件丢失返回 null。 */
    public Artifact find(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            return null;
        }
        synchronized (lock) {
            if (sweepLocked() > 0) {
                persistQuietlyLocked();
            }
            Artifact artifact = artifacts.get(id);
            if (artifact == null) {
                return null;
            }
            if (!Files.isRegularFile(artifact.path()) || !artifact.path().startsWith(exportsDirectory())) {
                artifacts.remove(id);
                persistQuietlyLocked();
                return null;
            }
            return artifact;
        }
    }

    /** 清理过期登记（删除文件）与文件缺失的登记，返回清理条数；供启动与定时任务调用。 */
    public int sweepExpired() {
        synchronized (lock) {
            int removed = sweepLocked();
            if (removed > 0) {
                persistQuietlyLocked();
                log.info("已清理 {} 个过期/失效的导出产物登记。", removed);
            }
            return removed;
        }
    }

    public Path exportsDirectory() {
        return data.cache().resolve("exports").toAbsolutePath().normalize();
    }

    public Path metadataFile() {
        return exportsDirectory().resolve(METADATA_FILE);
    }

    // ------------------------------------------------------------------ 内部（调用方持锁）

    private int sweepLocked() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (Map.Entry<String, Artifact> entry : artifacts.entrySet()) {
            Artifact artifact = entry.getValue();
            if (now >= artifact.expiresAt()) {
                if (artifacts.remove(entry.getKey(), artifact)) {
                    removed++;
                    deleteQuietly(artifact.path());
                }
                continue;
            }
            if (!Files.isRegularFile(artifact.path()) || !artifact.path().startsWith(exportsDirectory())) {
                if (artifacts.remove(entry.getKey(), artifact)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    private void loadLocked() {
        artifacts.clear();
        Path metadata = metadataFile();
        if (!Files.isRegularFile(metadata)) {
            return;
        }
        try {
            if (Files.size(metadata) > MAX_METADATA_BYTES) {
                throw new IOException("元数据文件过大");
            }
            JsonNode root = JSON.readTree(metadata.toFile());
            if (root == null || !root.isObject()
                    || root.path("schemaVersion").asInt(-1) != METADATA_SCHEMA_VERSION
                    || !root.path("artifacts").isArray()) {
                throw new IOException("元数据格式不受支持");
            }
            Path exports = exportsDirectory();
            for (JsonNode node : root.path("artifacts")) {
                String id = node.path("id").asText("");
                String relative = node.path("path").asText("");
                String filename = node.path("filename").asText("");
                String type = node.path("contentType").asText(DEFAULT_CONTENT_TYPE);
                if (!ID.matcher(id).matches() || relative.isBlank() || filename.isBlank()
                        || relative.contains("\\") || relative.contains("..") || relative.startsWith("/")) {
                    continue;
                }
                Path path = exports.resolve(relative).normalize();
                if (!path.startsWith(exports) || path.equals(exports) || !Files.isRegularFile(path)) {
                    continue;
                }
                long createdAt = node.path("createdAt").asLong(0);
                long expiresAt = node.path("expiresAt").asLong(0);
                long size = node.path("size").asLong(0);
                if (createdAt <= 0) {
                    createdAt = lastModified(path);
                }
                if (expiresAt <= 0) {
                    expiresAt = createdAt + TTL.toMillis();
                }
                if (size <= 0) {
                    try {
                        size = Files.size(path);
                    } catch (IOException ignored) {
                        size = 0;
                    }
                }
                String safeType = CONTENT_TYPE.matcher(type).matches() ? type : DEFAULT_CONTENT_TYPE;
                artifacts.put(id, new Artifact(id, path, safeFilename(filename, path.getFileName().toString()),
                        safeType, size, createdAt, expiresAt));
            }
        } catch (IOException | RuntimeException failure) {
            log.warn("产物元数据不可用，按空列表启动：{}", failure.toString());
            artifacts.clear();
        }
    }

    private void persistLocked() {
        try {
            persistUncheckedLocked();
        } catch (IOException failure) {
            log.warn("产物元数据保存失败：{}", failure.toString());
            throw new IllegalStateException("产物元数据保存失败，请检查数据目录权限。", failure);
        }
    }

    private void persistQuietlyLocked() {
        try {
            persistUncheckedLocked();
        } catch (IOException failure) {
            log.warn("产物元数据保存失败（不影响本次下载）：{}", failure.toString());
        }
    }

    private void persistUncheckedLocked() throws IOException {
        Path directory = exportsDirectory();
        Files.createDirectories(directory);
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", METADATA_SCHEMA_VERSION);
        ArrayNode list = root.putArray("artifacts");
        artifacts.values().stream()
                .sorted(Comparator.comparingLong(Artifact::createdAt))
                .forEach(artifact -> {
                    ObjectNode node = list.addObject();
                    node.put("id", artifact.id());
                    node.put("path", directory.relativize(artifact.path()).toString().replace('\\', '/'));
                    node.put("filename", artifact.filename());
                    node.put("contentType", artifact.contentType());
                    node.put("size", artifact.size());
                    node.put("createdAt", artifact.createdAt());
                    node.put("expiresAt", artifact.expiresAt());
                });
        Path temporary = Files.createTempFile(directory, ".artifacts-", ".tmp");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), root);
            InstanceStore.atomicMove(temporary, metadataFile());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException failure) {
            return System.currentTimeMillis();
        }
    }

    private void deleteQuietly(Path path) {
        try {
            if (path != null && path.startsWith(exportsDirectory())) {
                Files.deleteIfExists(path);
            }
        } catch (IOException failure) {
            log.debug("过期产物文件清理失败：{}", failure.toString());
        }
    }

    /** 下载文件名安全化：去掉路径与控制字符，防止 Content-Disposition 注入。 */
    private static String safeFilename(String filename, String fallback) {
        String value = filename == null ? "" : filename
                .replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f\\x7f]", "_")
                .strip();
        if (value.isBlank() || value.equals(".") || value.equals("..")) {
            value = fallback == null ? "download.bin" : fallback;
        }
        return value.length() > 160 ? value.substring(value.length() - 160) : value;
    }
}
