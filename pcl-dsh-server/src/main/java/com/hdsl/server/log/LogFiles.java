package com.hdsl.server.log;

import com.hdsl.server.config.DataRoot;
import com.hdsl.server.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 应用/任务日志：data/logs/launcher-YYYY-MM-DD.log（与桌面版同名，方案 §7.9）。
 *
 * <ul>
 *   <li>写入前统一经 {@link LogSanitizer} 脱敏单行化，绝不落盘密码、API Key、token。</li>
 *   <li>读取时再做一次脱敏（防御历史文件），并限制行数（1–2000）与读取字节数，避免大文件占满内存。</li>
 *   <li>日志目录不可写时静默降级：日志问题不得影响业务。</li>
 * </ul>
 */
@Component
public class LogFiles {

    private static final Logger log = LoggerFactory.getLogger(LogFiles.class);
    private static final Pattern LAUNCHER_FILE = Pattern.compile("launcher-\\d{4}-\\d{2}-\\d{2}\\.log");
    private static final int MAX_LINE_LENGTH = 2000;
    private static final int DEFAULT_TAIL_LINES = 800;
    private static final int MAX_TAIL_LINES = 2000;
    private static final long MAX_READ_BYTES = 4L * 1024 * 1024;

    private final DataRoot data;
    private final Object writeLock = new Object();

    public LogFiles(DataRoot data) {
        this.data = data;
    }

    /** 追加一行应用/任务日志；内容脱敏后单行化，失败静默降级。 */
    public void append(String line) {
        String safe = LogSanitizer.sanitizeLine(line, MAX_LINE_LENGTH);
        if (safe.isBlank()) {
            return;
        }
        String text = LocalTime.now().withNano(0) + "  " + safe + System.lineSeparator();
        Path file = file(LocalDate.now());
        try {
            Files.createDirectories(data.logs());
            synchronized (writeLock) {
                Files.writeString(file, text, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            log.debug("应用日志写入失败（不影响业务）：{}", e.toString());
        }
    }

    /**
     * 读取最近一个 launcher 日志文件末尾若干行（脱敏后）。
     *
     * @param lines 期望行数；≤0 用默认 800，超过 2000 按 2000 截断
     */
    public List<String> tail(int lines) {
        int limit = lines <= 0 ? DEFAULT_TAIL_LINES : Math.min(lines, MAX_TAIL_LINES);
        Path newest = newestLauncherFile();
        if (newest == null) {
            return List.of();
        }
        try {
            List<String> raw = readTail(newest, limit);
            List<String> result = new ArrayList<>(raw.size());
            for (String line : raw) {
                String safe = LogSanitizer.sanitizeLine(line, MAX_LINE_LENGTH);
                if (!safe.isBlank()) {
                    result.add(safe);
                }
            }
            return List.copyOf(result);
        } catch (IOException e) {
            log.warn("读取日志尾部失败（返回空列表）：{}", e.toString());
            return List.of();
        }
    }

    private Path file(LocalDate date) {
        return data.logs().resolve("launcher-" + date + ".log");
    }

    private Path newestLauncherFile() {
        try (Stream<Path> stream = Files.list(data.logs())) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> LAUNCHER_FILE.matcher(path.getFileName().toString()).matches())
                    .max(Comparator.comparingLong(LogFiles::lastModified))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static List<String> readTail(Path file, int limit) throws IOException {
        long size = Files.size(file);
        if (size <= MAX_READ_BYTES) {
            return lastLines(Files.readString(file, StandardCharsets.UTF_8), limit);
        }
        try (RandomAccessFile access = new RandomAccessFile(file.toFile(), "r")) {
            access.seek(size - MAX_READ_BYTES);
            byte[] bytes = new byte[(int) MAX_READ_BYTES];
            access.readFully(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8);
            int firstNewline = text.indexOf('\n');
            if (firstNewline >= 0) {
                text = text.substring(firstNewline + 1);
            }
            return lastLines(text, limit);
        }
    }

    private static List<String> lastLines(String text, int limit) {
        String value = text == null ? "" : text.stripTrailing();
        if (value.isEmpty()) {
            return List.of();
        }
        String[] all = value.split("\\R", -1);
        int start = Math.max(0, all.length - limit);
        List<String> result = new ArrayList<>(Math.min(limit, all.length));
        for (int index = start; index < all.length; index++) {
            if (!all[index].isBlank()) {
                result.add(all[index]);
            }
        }
        return result;
    }
}
