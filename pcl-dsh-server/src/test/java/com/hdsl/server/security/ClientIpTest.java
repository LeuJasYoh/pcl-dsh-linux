package com.hdsl.server.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 受信代理来源解析：仅回环来源信任 X-Forwarded-For/X-Real-IP，且只接受合法 IP 字面量。 */
class ClientIpTest {

    private static MockHttpServletRequest request(String remote, String xff, String realIp) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        if (xff != null) {
            request.addHeader("X-Forwarded-For", xff);
        }
        if (realIp != null) {
            request.addHeader("X-Real-IP", realIp);
        }
        return request;
    }

    @Test
    void directClientCannotForgeForwardedHeaders() {
        assertEquals("203.0.113.9",
                ClientIp.of(request("203.0.113.9", "1.2.3.4", "5.6.7.8")),
                "非回环直连必须忽略伪造的 XFF/X-Real-IP");
    }

    @Test
    void loopbackProxyTrustsFirstValidForwardedIp() {
        assertEquals("1.2.3.4",
                ClientIp.of(request("127.0.0.1", "garbage, 1.2.3.4, 10.0.0.1", null)),
                "回环代理应取 XFF 第一个合法 IP");
        assertEquals("198.51.100.7",
                ClientIp.of(request("::1", "198.51.100.7", null)));
        assertEquals("198.51.100.8",
                ClientIp.of(request("0:0:0:0:0:0:0:1", " 198.51.100.8 ", null)));
    }

    @Test
    void loopbackFallsBackToRealIpThenRemote() {
        assertEquals("9.9.9.9", ClientIp.of(request("127.0.0.1", null, "9.9.9.9")));
        assertEquals("127.0.0.1", ClientIp.of(request("127.0.0.1", "not-an-ip, <script>", "also-bad")));
        assertEquals("::ffff:127.0.0.1",
                ClientIp.of(request("::ffff:127.0.0.1", "bad", null)));
    }

    @Test
    void acceptsIpv6AndPortForms() {
        assertEquals("2001:db8::1", ClientIp.of(request("127.0.0.1", "2001:db8::1", null)));
        assertEquals("::1", ClientIp.of(request("127.0.0.1", "[::1]", null)));
        assertEquals("1.2.3.4", ClientIp.of(request("127.0.0.1", "1.2.3.4:8080", null)));
    }

    @Test
    void handlesMissingRequestAndBlankRemote() {
        assertEquals("unknown", ClientIp.of(null));
        assertEquals("unknown", ClientIp.of(request("", null, null)));
    }
}
