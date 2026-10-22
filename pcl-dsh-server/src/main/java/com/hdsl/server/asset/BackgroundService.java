package com.hdsl.server.asset;

import com.hdsl.core.InstanceStore;
import com.hdsl.server.api.ApiDtos;
import com.hdsl.server.api.ApiException;
import com.hdsl.server.config.DataRoot;
import com.hdsl.server.job.JobManager;
import com.hdsl.server.log.LogFiles;
import com.hdsl.server.security.AuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 自定义背景资源（方案 §4.7 / §5.7）：上传校验（≤20 MB、PNG/JPEG、尺寸/像素上限）、
 * 存储到 data/assets/backgrounds、资源读取与 settings.background 引用校验。
 *
 * <p>安全：只按 ImageIO 实际解码出的格式决定扩展名，不信任客户端文件名/Content-Type；
 * 上传/读取路径固定且限定在 data/assets/backgrounds 内，名称使用严格白名单防路径穿越。</p>
 */
@Component
public class BackgroundService {

    private static final Logger log = LoggerFactory.getLogger(BackgroundService.class);
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final int MAX_DIMENSION = 8192;
    private static final long MAX_PIXELS = 40_000_000L;
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,96}\\.(?:png|jpg|jpeg)");
    private static final Pattern REFERENCE = Pattern.compile(
            "builtin:whale-0[1-5]|/api/assets/backgrounds/[A-Za-z0-9_-]{1,96}\\.(?:png|jpg|jpeg)");
    private static final String INVALID_IMAGE = "背景图片格式不受支持，请上传 PNG 或 JPEG 图片。";

    private final DataRoot data;
    private final JobManager jobs;
    private final AuditLog audit;
    private final LogFiles logs;

    public BackgroundService(DataRoot data, JobManager jobs, AuditLog audit, LogFiles logs) {
        this.data = data;
        this.jobs = jobs;
        this.audit = audit;
        this.logs = logs;
    }

    /** 已解析的背景文件（路径 + 响应 Content-Type）。 */
    public record BackgroundFile(Path path, String contentType) {
    }

    private record Decoded(int width, int height, String extension) {
    }

    /** POST /api/settings/background：校验并保存，返回可直接写入 settings.background 的地址。 */
    public ApiDtos.BackgroundDto upload(MultipartFile file, String ip) {
        jobs.ensureIdle();
        if (file == null || file.isEmpty()) {
            throw ApiException.validation("请选择要上传的背景图片。");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.validation("背景图片不能超过 20 MB。");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException failure) {
            log.warn("读取上传背景失败：{}", failure.toString());
            throw ApiException.internal("读取上传图片失败，请重试。");
        }
        if (bytes.length == 0) {
            throw ApiException.validation("请选择要上传的背景图片。");
        }
        if (bytes.length > MAX_BYTES) {
            throw ApiException.validation("背景图片不能超过 20 MB。");
        }
        Decoded decoded = decode(bytes);
        String name = UUID.randomUUID() + "." + decoded.extension();
        Path directory = backgrounds();
        Path target = directory.resolve(name);
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "background-", ".tmp");
            try {
                Files.write(temporary, bytes);
                InstanceStore.atomicMove(temporary, target);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failure) {
            log.warn("保存背景图片失败：{}", failure.toString());
            throw ApiException.internal("保存背景图片失败，请检查数据目录权限。");
        }
        String reference = "/api/assets/backgrounds/" + name;
        audit.record("BACKGROUND_UPLOADED", ip, "name=" + name + " "
                + decoded.width() + "x" + decoded.height() + " " + decoded.extension());
        logs.append("已上传背景图片：" + name + "（" + decoded.width() + "x" + decoded.height() + "）");
        return new ApiDtos.BackgroundDto(reference);
    }

    /** PUT /api/settings 的 background 校验：空 / 内置 / 本机上传资源地址，其余 400。 */
    public String validateReference(String background) {
        String value = background == null ? "" : background.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (!REFERENCE.matcher(value).matches()) {
            throw ApiException.validation("背景必须为内置背景（builtin:whale-01…05）或本机上传的背景地址。");
        }
        return value;
    }

    /** GET /api/assets/backgrounds/{name}：只读读取，文件名白名单 + 目录内路径校验。 */
    public BackgroundFile resolve(String name) {
        String value = name == null ? "" : name.trim();
        if (!SAFE_NAME.matcher(value).matches()) {
            throw ApiException.validation("背景资源名称无效。");
        }
        Path path = backgrounds().resolve(value).normalize();
        if (!path.startsWith(backgrounds())) {
            throw ApiException.validation("背景资源名称无效。");
        }
        if (!Files.isRegularFile(path)) {
            throw ApiException.notFound("背景资源不存在。");
        }
        return new BackgroundFile(path, contentTypeFor(value));
    }

    private Path backgrounds() {
        return data.assets().resolve("backgrounds").toAbsolutePath().normalize();
    }

    private Decoded decode(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw ApiException.validation(INVALID_IMAGE);
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw ApiException.validation(INVALID_IMAGE);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName() == null ? "" : reader.getFormatName().toLowerCase(Locale.ROOT);
                String extension = switch (format) {
                    case "png" -> "png";
                    case "jpeg", "jpg" -> "jpg";
                    default -> null;
                };
                if (extension == null) {
                    throw ApiException.validation(INVALID_IMAGE);
                }
                int width;
                int height;
                try {
                    width = reader.getWidth(0);
                    height = reader.getHeight(0);
                } catch (IOException | RuntimeException failure) {
                    throw ApiException.validation("背景图片已损坏或无法解码。");
                }
                if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
                        || (long) width * height > MAX_PIXELS) {
                    throw ApiException.validation("背景图片尺寸需在 1–8192 像素之间，且总像素不超过 4000 万。");
                }
                try {
                    BufferedImage image = reader.read(0);
                    if (image == null) {
                        throw ApiException.validation("背景图片已损坏或无法解码。");
                    }
                } catch (IOException | RuntimeException failure) {
                    throw ApiException.validation("背景图片已损坏或无法解码。");
                }
                return new Decoded(width, height, extension);
            } finally {
                reader.dispose();
            }
        } catch (IOException failure) {
            throw ApiException.validation("背景图片已损坏或无法解码。");
        }
    }

    private static String contentTypeFor(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") ? "image/png" : "image/jpeg";
    }
}
