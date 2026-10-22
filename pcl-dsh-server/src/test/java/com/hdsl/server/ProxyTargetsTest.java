package com.hdsl.server.proxy;

import com.hdsl.core.Instance;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 反代纯函数单测：SSRF 白名单、token 引导、Location/Set-Cookie 重写。 */
class ProxyTargetsTest {

    private static final int PORT = 3080;

    private static Instance instance() {
        return new Instance("i-test", "测试实例", "0.1.0-rc.6", "web", PORT, "", "");
    }

    @Test
    void parsesInstanceIdAndRemainder() {
        assertEquals("i-test", ProxyTargets.instanceId("/workbench/i-test/"));
        assertEquals("i-test", ProxyTargets.instanceId("/workbench/i-test/assets/a.js"));
        assertEquals("", ProxyTargets.remainder("/workbench/i-test", "i-test"));
        assertEquals("/assets/a.js", ProxyTargets.remainder("/workbench/i-test/assets/a.js", "i-test"));
        assertEquals(null, ProxyTargets.instanceId("/api/instances"));
        assertEquals(null, ProxyTargets.remainder("/workbench/other/x", "i-test"));
    }

    @Test
    void buildsLoopbackTargetAndIgnoresUntrustedBootstrap() {
        URI bootstrap = URI.create("http://127.0.0.1:" + PORT + "/?token=secret");
        URI target = ProxyTargets.upstreamUri(instance(), bootstrap, "/assets/a.js", "v=1");
        assertEquals("http", target.getScheme());
        assertEquals("127.0.0.1", target.getHost());
        assertEquals(PORT, target.getPort());
        assertEquals("/assets/a.js", target.getRawPath());
        assertEquals("v=1", target.getRawQuery());

        // 非回环/端口不匹配的 bootstrap 不得影响出站目标，也不会带出 token。
        URI hostile = URI.create("http://10.0.0.5:9999/?token=stolen");
        URI safe = ProxyTargets.upstreamUri(instance(), hostile, "/", null);
        assertEquals("127.0.0.1", safe.getHost());
        assertEquals(PORT, safe.getPort());
        assertEquals("/", safe.getRawPath());
        assertEquals(null, safe.getRawQuery());
        assertFalse(safe.toString().contains("stolen"));
    }

    @Test
    void rejectsTraversalAndControlCharacters() {
        URI bootstrap = URI.create("http://127.0.0.1:" + PORT + "/");
        assertThrows(IllegalArgumentException.class,
                () -> ProxyTargets.upstreamUri(instance(), bootstrap, "/../config", null));
        assertThrows(IllegalArgumentException.class,
                () -> ProxyTargets.upstreamUri(instance(), bootstrap, "/a" + (char) 0 + "b", null));
        assertThrows(IllegalArgumentException.class,
                () -> ProxyTargets.upstreamUri(instance(), bootstrap, "relative", null));
    }

    @Test
    void buildsBootstrapLocationOnlyForTrustedBootstrap() {
        URI bootstrap = URI.create("http://127.0.0.1:" + PORT + "/?token=abc");
        assertEquals("/workbench/i-test/?token=abc",
                ProxyTargets.bootstrapLocation("i-test", instance(), bootstrap));
        URI hostile = URI.create("http://10.0.0.5:" + PORT + "/?token=abc");
        assertEquals("/workbench/i-test/",
                ProxyTargets.bootstrapLocation("i-test", instance(), hostile));
    }

    @Test
    void detectsTokenQuery() {
        assertTrue(ProxyTargets.hasToken("token=abc"));
        assertTrue(ProxyTargets.hasToken("a=1&token=abc"));
        assertFalse(ProxyTargets.hasToken(""));
        assertFalse(ProxyTargets.hasToken("token="));
        assertFalse(ProxyTargets.hasToken("other=1"));
    }

    @Test
    void rewritesLocation() {
        assertEquals("/workbench/i-test/landing?x=1",
                ProxyTargets.rewriteLocation("http://127.0.0.1:" + PORT + "/landing?x=1", "i-test", PORT));
        assertEquals("/workbench/i-test/", ProxyTargets.rewriteLocation("/", "i-test", PORT));
        assertEquals("./", ProxyTargets.rewriteLocation("./", "i-test", PORT));
        assertEquals("assets/a.js", ProxyTargets.rewriteLocation("assets/a.js", "i-test", PORT));
        assertEquals("https://example.com/x", ProxyTargets.rewriteLocation("https://example.com/x", "i-test", PORT));
        assertEquals("http://127.0.0.1:9999/x",
                ProxyTargets.rewriteLocation("http://127.0.0.1:9999/x", "i-test", PORT));
    }

    @Test
    void rewritesSetCookie() {
        String rewritten = ProxyTargets.rewriteSetCookie(
                "dsh-auth-abcdef12=cookie-value; HttpOnly; Secure; Domain=127.0.0.1; Path=/; SameSite=None",
                "i-test");
        assertTrue(rewritten.startsWith("dsh-auth-abcdef12=cookie-value"));
        assertTrue(rewritten.contains("HttpOnly"));
        assertTrue(rewritten.contains("Path=/workbench/i-test"));
        assertTrue(rewritten.contains("SameSite=Lax"));
        assertFalse(rewritten.toLowerCase().contains("domain="));
        assertFalse(rewritten.toLowerCase().contains("secure"));
        assertFalse(rewritten.toLowerCase().contains("none"));
    }

    @Test
    void identifiesHopByHopHeaders() {
        assertTrue(ProxyTargets.hopByHop("Connection"));
        assertTrue(ProxyTargets.hopByHop("Transfer-Encoding"));
        assertTrue(ProxyTargets.hopByHop("Upgrade"));
        assertFalse(ProxyTargets.hopByHop("Content-Type"));
        assertFalse(ProxyTargets.hopByHop("Cookie"));
    }
}
