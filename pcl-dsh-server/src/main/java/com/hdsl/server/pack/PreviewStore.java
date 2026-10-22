package com.hdsl.server.pack;

import com.hdsl.pack.PackInspection;
import com.hdsl.server.api.ApiException;
import com.hdsl.server.config.DataRoot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 整合包上传预览登记（方案 §4.7）：内存表 + 上传文件 + SHA-256 + 已校验的 PackInspection。
 *
 * <ul>
 *   <li>TTL 30 分钟，过期后删除登记与上传文件（懒清理）；</li>
 *   <li>文件固定在 data/cache/uploads 内，删除前校验路径前缀；</li>
 *   <li>导入时用 previewId 复用，无需重复上传；导入前会重新核对 SHA-256 防换包。</li>
 * </ul>
 */
@Component
public class PreviewStore {

    private static final Logger log = LoggerFactory.getLogger(PreviewStore.class);
    public static final Duration TTL = Duration.ofMinutes(30);

    private final DataRoot data;
    private final Map<String, Preview> previews = new ConcurrentHashMap<>();

    public PreviewStore(DataRoot data) {
        this.data = data;
    }

    public record Preview(String id, Path archive, String sha256, PackInspection inspection,
                          long size, long createdAt) {
    }

    /** 分配一个新的上传文件路径（data/cache/uploads/<uuid>.zip）。 */
    public Path allocate() throws IOException {
        Path directory = uploads();
        Files.createDirectories(directory);
        return directory.resolve(UUID.randomUUID().toString().replace("-", "") + ".zip");
    }

    /** 登记已上传并校验完成的整合包。 */
    public synchronized Preview register(Path archive, String sha256, PackInspection inspection, long size) {
        Path normalized = archive.toAbsolutePath().normalize();
        if (!normalized.startsWith(uploads()) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw ApiException.internal("整合包上传文件不可用，请重新上传。");
        }
        Preview preview = new Preview("p-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                normalized, sha256, inspection, size, System.currentTimeMillis());
        previews.put(preview.id(), preview);
        return preview;
    }

    /** 查找未过期的预览；未知/过期/文件丢失返回 null。 */
    public Preview find(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        sweep();
        Preview preview = previews.get(id.trim());
        if (preview == null) {
            return null;
        }
        if (System.currentTimeMillis() - preview.createdAt() > TTL.toMillis()
                || !Files.isRegularFile(preview.archive(), LinkOption.NOFOLLOW_LINKS)) {
            remove(id.trim());
            return null;
        }
        return preview;
    }

    /** 移除登记并删除上传文件（成功后调用）。 */
    public void remove(String id) {
        Preview preview = id == null ? null : previews.remove(id);
        if (preview != null) {
            deleteQuietly(preview.archive());
        }
    }

    public Path uploads() {
        return data.cache().resolve("uploads").toAbsolutePath().normalize();
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Preview> entry : previews.entrySet()) {
            Preview preview = entry.getValue();
            if (now - preview.createdAt() <= TTL.toMillis()) {
                continue;
            }
            if (previews.remove(entry.getKey(), preview)) {
                deleteQuietly(preview.archive());
            }
        }
    }

    private void deleteQuietly(Path archive) {
        try {
            if (archive != null && archive.toAbsolutePath().normalize().startsWith(uploads())) {
                Files.deleteIfExists(archive);
            }
        } catch (IOException failure) {
            log.debug("整合包上传文件清理失败：{}", failure.toString());
        }
    }
}
