package com.hdsl.server.proxy;

import com.hdsl.core.Instance;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * /workbench 反代的纯函数工具：路径解析、URL/Cookie 重写、SSRF 白名单校验。
 *
 * <p>安全约束：出站目标永远由 {@code 127.0.0.1 + instance.port()} 构造，host 不接受任何请求输入；
 * 启动 URI（含一次性 token）仅在通过回环 + 实例端口校验后用于 token 引导与 base 路径。</p>
 */
public final class ProxyTargets {

    public static final String PREFIX = "/workbench/";
    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "localhost", "::1", "[::1]");
    private static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade");

    private ProxyTargets() {
    }

    /** 从原始请求 URI（不含查询串）解析实例 id；不是 /workbench/ 前缀时返回 null。 */
    public static String instanceId(String rawUri) {
        if (rawUri == null || !rawUri.startsWith(PREFIX)) {
            return null;
        }
        int start = PREFIX.length();
        int end = rawUri.indexOf('/', start);
        String id = end < 0 ? rawUri.substring(start) : rawUri.substring(start, end);
        return id.isEmpty() ? null : id;
    }

    /** 去掉 /workbench/{id} 前缀后的剩余原始路径（可能为 ""、"/"、"/assets/a.js"）。 */
    public static String remainder(String rawUri, String instanceId) {
        String prefix = PREFIX + instanceId;
        if (rawUri == null || !rawUri.startsWith(prefix)) {
            return null;
        }
        return rawUri.substring(prefix.length());
    }

    /** 是否为允许的回环 HTTP 地址（用于校验 ProcessService 内存中的启动 URI）。 */
    public static boolean isLoopbackHttp(URI uri) {
        return uri != null && "http".equalsIgnoreCase(uri.getScheme())
                && uri.getUserInfo() == null && uri.getHost() != null
                && LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))
                && (uri.getPort() == -1 || (uri.getPort() >= 1 && uri.getPort() <= 65535));
    }

    /**
     * 构造上游回环目标：http://127.0.0.1:{instance.port}{basePath+剩余路径}?{原始查询}。
     * 剩余路径含控制字符或 ".." 时拒绝；构造后再校验 host/port/userInfo 白名单。
     */
    public static URI upstreamUri(Instance instance, URI bootstrap, String rawRemainder, String rawQuery) {
        int port = instance.port();
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("实例端口不在允许范围内。");
        }
        String basePath = "/";
        if (isLoopbackHttp(bootstrap) && bootstrap.getPort() == port) {
            String path = bootstrap.getRawPath();
            if (path != null && !path.isEmpty()) {
                basePath = path;
            }
        }
        String remainder = rawRemainder == null || rawRemainder.isEmpty() ? "/" : rawRemainder;
        if (!remainder.startsWith("/") || unsafe(remainder) || unsafe(basePath)) {
            throw new IllegalArgumentException("请求路径无效。");
        }
        String path = join(basePath, remainder);
        String value = "http://127.0.0.1:" + port + path
                + (rawQuery == null || rawQuery.isEmpty() ? "" : "?" + rawQuery);
        URI target;
        try {
            target = URI.create(value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("请求路径无效。", failure);
        }
        if (!"http".equals(target.getScheme()) || !"127.0.0.1".equals(target.getHost())
                || target.getPort() != port || target.getUserInfo() != null) {
            throw new IllegalArgumentException("反代目标不在白名单内。");
        }
        return target;
    }

    /** 首次访问无 token/无 dsh-auth Cookie 时的 302 目标：/workbench/{id}/ + 启动 URI 的查询串（含 token）。 */
    public static String bootstrapLocation(String instanceId, Instance instance, URI bootstrap) {
        String root = PREFIX + instanceId + "/";
        if (!isLoopbackHttp(bootstrap) || bootstrap.getPort() != instance.port()) {
            return root;
        }
        String query = bootstrap.getRawQuery();
        return query == null || query.isEmpty() ? root : root + "?" + query;
    }

    /** 请求查询串中是否存在非空 token 参数（token 只用于首次引导）。 */
    public static boolean hasToken(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return false;
        }
        for (String part : rawQuery.split("&")) {
            int equals = part.indexOf('=');
            String name = equals < 0 ? part : part.substring(0, equals);
            String value = equals < 0 ? "" : part.substring(equals + 1);
            if ("token".equals(name) && !value.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Location 重写：指向实例回环端口的绝对地址 → /workbench/{id}/...；绝对路径 → 加前缀；
     * 相对地址（如 "./"）原样返回；外部地址不改写。
     */
    public static String rewriteLocation(String location, String instanceId, int port) {
        if (location == null || location.isBlank()) {
            return location;
        }
        String value = location.trim();
        if (value.startsWith("//")) {
            return value;
        }
        try {
            URI uri = URI.create(value);
            if (uri.isAbsolute()) {
                if (("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        && isLoopbackHttp(uri) && uri.getPort() == port) {
                    return withPrefix(instanceId, uri.getRawPath(), uri.getRawQuery());
                }
                return value;
            }
            if (value.startsWith("/")) {
                return withPrefix(instanceId, uri.getRawPath(), uri.getRawQuery());
            }
            return value;
        } catch (IllegalArgumentException failure) {
            return value;
        }
    }

    private static String withPrefix(String instanceId, String rawPath, String rawQuery) {
        String path = rawPath == null || rawPath.isEmpty() ? "/" : rawPath;
        String prefixed = PREFIX + instanceId + path;
        return rawQuery == null || rawQuery.isEmpty() ? prefixed : prefixed + "?" + rawQuery;
    }

    /**
     * Set-Cookie 重写：去掉 Domain/Secure，Path 改为 /workbench/{id}；
     * SameSite=None 降级为 Lax（HTTP 部署下 SameSite=None 且无 Secure 会被浏览器拒绝）。
     */
    public static String rewriteSetCookie(String header, String instanceId) {
        if (header == null || header.isBlank()) {
            return header;
        }
        String[] parts = header.split(";");
        List<String> attributes = new ArrayList<>();
        int first = -1;
        for (int index = 0; index < parts.length; index++) {
            if (!parts[index].isBlank()) {
                first = index;
                break;
            }
        }
        if (first < 0) {
            return header;
        }
        attributes.add(parts[first].trim());
        for (int index = first + 1; index < parts.length; index++) {
            String attribute = parts[index].trim();
            if (attribute.isEmpty()) {
                continue;
            }
            String lower = attribute.toLowerCase(Locale.ROOT);
            if (lower.startsWith("domain=") || lower.startsWith("secure") || lower.startsWith("path=")) {
                continue;
            }
            if (lower.startsWith("samesite=") && lower.contains("none")) {
                attributes.add("SameSite=Lax");
                continue;
            }
            attributes.add(attribute);
        }
        attributes.add("Path=" + PREFIX + instanceId);
        return String.join("; ", attributes);
    }

    public static boolean hopByHop(String headerName) {
        return headerName != null && HOP_BY_HOP.contains(headerName.toLowerCase(Locale.ROOT));
    }

    private static boolean unsafe(String path) {
        return path.contains("..") || path.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f);
    }

    private static String join(String basePath, String remainder) {
        boolean baseSlash = basePath.endsWith("/");
        boolean remainderSlash = remainder.startsWith("/");
        if (baseSlash && remainderSlash) {
            return basePath + remainder.substring(1);
        }
        if (!baseSlash && !remainderSlash) {
            return basePath + "/" + remainder;
        }
        return basePath + remainder;
    }
}
