package com.hdsl.server.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 来源 IP 解析：只有来自回环地址（Nginx 反代）的请求才信任 X-Forwarded-For / X-Real-IP，
 * 防止外部直接伪造请求头绕过登录限流。
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String remote = request.getRemoteAddr();
        if (isLoopback(remote)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                for (String candidate : forwarded.split(",")) {
                    String trimmed = candidate.trim();
                    if (!trimmed.isEmpty() && trimmed.length() <= 64) {
                        return trimmed;
                    }
                }
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank() && realIp.trim().length() <= 64) {
                return realIp.trim();
            }
        }
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }

    private static boolean isLoopback(String address) {
        return "127.0.0.1".equals(address)
                || "::1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address)
                || "::ffff:127.0.0.1".equals(address);
    }
}
