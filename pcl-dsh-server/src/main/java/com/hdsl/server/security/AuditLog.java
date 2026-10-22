package com.hdsl.server.security;

import com.hdsl.server.config.DataRoot;
import com.hdsl.server.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 审计日志：data/logs/audit-YYYY-MM-DD.log。
 * 只记录事件名、来源 IP 与经过脱敏的简短说明，绝不写入密码、API Key、会话或 CSRF Token。
 */
@Component
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DataRoot data;
    private final Object writeLock = new Object();

    public AuditLog(DataRoot data) {
        this.data = data;
    }

    /**
     * @param event  事件名（固定字符串，如 LOGIN_SUCCESS）
     * @param ip     来源 IP
     * @param detail 脱敏后的说明，可为空
     */
    public void record(String event, String ip, String detail) {
        String line = LocalDateTime.now().format(TIME)
                + " [" + safe(event, 64) + "]"
                + " ip=" + safe(ip, 64)
                + (detail == null || detail.isBlank() ? "" : " " + LogSanitizer.sanitizeLine(detail, 300));
        Path file = data.logs().resolve("audit-" + LocalDate.now() + ".log");
        try {
            Files.createDirectories(data.logs());
            synchronized (writeLock) {
                Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            log.warn("审计日志写入失败：{}", e.toString());
        }
    }

    private static String safe(String value, int maxLength) {
        return value == null ? "-" : LogSanitizer.oneLine(value.replaceAll("[\\r\\n\\t\\s]+", "_"), maxLength);
    }
}
