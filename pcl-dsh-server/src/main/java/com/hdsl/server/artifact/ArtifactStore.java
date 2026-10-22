package com.hdsl.server.artifact;

import com.hdsl.server.config.DataRoot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 导出产物登记与下载基础设施（方案 §4.7，供后续整合包导出使用）。
 *
 * <ul>
 *   <li>只登记 data/cache/exports 内的普通文件，避免任意文件下载；</li>
 *   <li>内存映射 id → 文件，TTL 24 小时，过期登记与文件一并清理（懒清理）；</li>
 *   <li>id 为随机 32 位十六进制，不可枚举；文件名下载时做安全化。</li>
 * </ul>
 */
@Component
public class ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStore.class);
    public static final Duration TTL = Duration.ofHours(24);
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[a-f0-9]{32}");
    private static final java.util.regex.Pattern CONTENT_TYPE =
            java.util.regex.Pattern.compile("[a-z0-9][a-z0-9.+-]{0,63}/[a-z0-9][a-z0-9.+-]{0,63}");
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final DataRoot data;
    private final Map<String, Artifact> artifacts = new ConcurrentHashMap<>();

    public ArtifactStore(DataRoot data) {
        this.data = data;
    }

    public record Artifact(String id, Path path, String filename, String contentType, long size,
                           long createdAt) {
    }

    /**
     * 登记导出目录内的文件。
     *
     * @return 下载 id（32 位十六进制）
     * @throws IllegalArgumentException 文件不在 data/cache/exports 内、不存在或不可读
     */
    public String register(Path file, String filename, String contentType) {
        sweep();
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
        String id = UUID.randomUUID().toString().replace("-", "");
        artifacts.put(id, new Artifact(id, normalized, safeName, type, size, System.currentTimeMillis()));
        return id;
    }

    /** 查询未过期且仍位于导出目录内的产物；未知/过期/文件丢失返回 null。 */
    public Artifact find(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            return null;
        }
        sweep();
        Artifact artifact = artifacts.get(id);
        if (artifact == null) {
            return null;
        }
        if (!Files.isRegularFile(artifact.path()) || !artifact.path().startsWith(exportsDirectory())) {
            artifacts.remove(id);
            return null;
        }
        return artifact;
    }

    public Path exportsDirectory() {
        return data.cache().resolve("exports").toAbsolutePath().normalize();
    }

    /** 清理过期登记及其产物文件（只删除登记过的导出目录文件）。 */
    private void sweep() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Artifact> entry : artifacts.entrySet()) {
            Artifact artifact = entry.getValue();
            if (now - artifact.createdAt() <= TTL.toMillis()) {
                continue;
            }
            if (artifacts.remove(entry.getKey(), artifact)) {
                try {
                    Path path = artifact.path();
                    if (path.startsWith(exportsDirectory())) {
                        Files.deleteIfExists(path);
                    }
                } catch (IOException failure) {
                    log.debug("过期产物清理失败：{}", failure.toString());
                }
            }
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
