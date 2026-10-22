package com.hdsl.server.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 来源 IP 解析（方案 §4.4）：只有 remoteAddr 为回环地址（Nginx 反代）时才信任
 * X-Forwarded-For / X-Real-IP，且只接受合法 IP 字面量，防止外部直连伪造请求头绕过登录限流。
 *
 * <p>X-Forwarded-For 取“第一个合法 IP”（代理链最左侧即原始客户端）；解析失败时回退到
 * X-Real-IP，再回退到 remoteAddr。不做 DNS 解析，避免请求触发反向查询。</p>
 */
public final class ClientIp {

    private static final int MAX_HEADER_LENGTH = 512;
    private static final int MAX_IP_LENGTH = 64;

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String remote = request.getRemoteAddr();
        if (isLoopback(remote)) {
            String forwarded = firstValidIp(request.getHeader("X-Forwarded-For"));
            if (forwarded != null) {
                return forwarded;
            }
            String realIp = validIp(request.getHeader("X-Real-IP"));
            if (realIp != null) {
                return realIp;
            }
        }
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }

    /** 逗号分隔的代理链中取第一个合法 IP 字面量；无合法项返回 null。 */
    static String firstValidIp(String header) {
        if (header == null || header.isBlank() || header.length() > MAX_HEADER_LENGTH) {
            return null;
        }
        for (String candidate : header.split(",")) {
            String valid = validIp(candidate);
            if (valid != null) {
                return valid;
            }
        }
        return null;
    }

    /** 校验并规范化单个 IP 字面量（IPv4/IPv6，允许带端口形式 "1.2.3.4:5678" 仅取地址部分）。 */
    static String validIp(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.isEmpty() || candidate.length() > MAX_IP_LENGTH) {
            return null;
        }
        if (candidate.startsWith("[")) {
            int end = candidate.indexOf(']');
            if (end > 1) {
                candidate = candidate.substring(1, end);
            }
        } else if (candidate.indexOf(':') >= 0 && candidate.indexOf('.') >= 0) {
            // IPv4:port 形式（IPv6 本身含多个冒号，不做该拆分）
            int firstColon = candidate.indexOf(':');
            if (candidate.indexOf(':', firstColon + 1) < 0) {
                candidate = candidate.substring(0, firstColon);
            }
        }
        if (!isIpLiteral(candidate)) {
            return null;
        }
        return candidate;
    }

    /** 只接受十六进制/数字/点/冒号组成的地址，避免 InetAddress 触发 DNS 查询。 */
    private static boolean isIpLiteral(String candidate) {
        if (candidate.isEmpty() || candidate.length() > MAX_IP_LENGTH) {
            return false;
        }
        for (int index = 0; index < candidate.length(); index++) {
            char ch = candidate.charAt(index);
            boolean allowed = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f')
                    || (ch >= 'A' && ch <= 'F') || ch == '.' || ch == ':';
            if (!allowed) {
                return false;
            }
        }
        try {
            java.net.InetAddress.getByName(candidate);
            return true;
        } catch (java.net.UnknownHostException | IllegalArgumentException failure) {
            return false;
        }
    }

    private static boolean isLoopback(String address) {
        return "127.0.0.1".equals(address)
                || "::1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address)
                || "::ffff:127.0.0.1".equals(address);
    }
}
