package com.hdsl.server.pack;

import com.hdsl.pack.PackInspection;
import com.hdsl.server.api.ApiException;
import com.hdsl.server.config.DataRoot;
import jakarta.annotation.PostConstruct;
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
import java.util.stream.Stream;

/**
 * 整合包上传预览登记（方案 §4.7）：内存表 + 上传文件 + SHA-256 + 已校验的 PackInspection。
 *
 * <ul>
 *   <li>TTL 30 分钟；启动时清理上次进程遗留的上传文件，运行期由定时任务清理过期登记；</li>
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

    /**
     * 启动清理：预览登记只在内存中，重启后之前的上传文件永远无法再被引用，
     * 因此直接清空 data/cache/uploads 下的遗留文件（只删除本目录普通文件）。
     */
    @PostConstruct
    public synchronized void initialize() {
        Path directory = uploads();
        try {
            Files.createDirectories(directory);
        } catch (IOException failure) {
            log.warn("预览上传目录不可用：{}", failure.toString());
            return;
        }
        int removed = 0;
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                try {
                    Files.deleteIfExists(file);
                    removed++;
                } catch (IOException failure) {
                    log.warn("遗留预览文件清理失败：{}", failure.toString());
                }
            }
        } catch (IOException failure) {
            log.warn("遗留预览文件清理失败：{}", failure.toString());
        }
        if (removed > 0) {
            log.info("已清理 {} 个上次运行遗留的整合包上传文件。", removed);
        }
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
        sweepExpired();
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

    /** 清理过期登记与上传文件，返回清理条数；供启动与定时任务调用。 */
    public synchronized int sweepExpired() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (Map.Entry<String, Preview> entry : previews.entrySet()) {
            Preview preview = entry.getValue();
            if (now - preview.createdAt() <= TTL.toMillis()) {
                continue;
            }
            if (previews.remove(entry.getKey(), preview)) {
                deleteQuietly(preview.archive());
                removed++;
            }
        }
        if (removed > 0) {
            log.info("已清理 {} 个过期的整合包预览。", removed);
        }
        return removed;
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
