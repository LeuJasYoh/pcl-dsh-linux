package com.hdsl.server.util;

/** 日志与 SSE 输出脱敏：复用桌面版 ProcessService.redact 的规则，并保证单行化。 */
public final class LogSanitizer {

    private LogSanitizer() {
    }

    public static String redact(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replaceAll("(?i)(authorization\\s*[:=]\\s*)(?:bearer\\s+|basic\\s+)?[^\\s,]+", "$1[已隐藏]")
                .replaceAll("(?i)([\"']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|secret)[\"']?\\s*[:=]\\s*[\"']?)[^\\s,\"'&}]+", "$1[已隐藏]")
                .replaceAll("sk-[A-Za-z0-9_-]{12,}", "[已隐藏]");
    }

    /** 去换行并截断，避免审计/SSE 单行协议被破坏。 */
    public static String oneLine(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("[\\r\\n\\t]", " ").replaceAll("\\s{2,}", " ").strip();
        if (cleaned.length() <= maxLength) {
            return cleaned;
        }
        return cleaned.substring(0, maxLength) + "…";
    }

    public static String sanitizeLine(String value, int maxLength) {
        return oneLine(redact(value), maxLength);
    }
}
