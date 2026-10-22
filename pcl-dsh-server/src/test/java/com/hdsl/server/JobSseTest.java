package com.hdsl.server;

import com.hdsl.server.job.JobManager;
import com.hdsl.server.security.ServerCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSE 端到端测试（真实内嵌 Tomcat + JDK HttpClient）：
 * 订阅 /api/jobs/{id}/events，验证 state / log / result 事件、SSE Content-Type 与任务结束后流关闭。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JobSseTest {

    private static final Path DATA_ROOT = TestDataRoots.prepare("sse");
    private static final String PASSWORD = "SseTestPass!2026";

    @DynamicPropertySource
    static void dataRoot(DynamicPropertyRegistry registry) {
        registry.add("pcl.data-root", DATA_ROOT::toString);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JobManager jobs;

    @Autowired
    private ServerCredentials credentials;

    @BeforeEach
    void ensurePasswordChanged() throws Exception {
        if (credentials.initialPasswordPending()) {
            String initial = Files.readString(DATA_ROOT.resolve("initial-password")).strip();
            credentials.changePassword(initial, PASSWORD);
        }
    }

    @Test
    void sseStreamsStateLogAndResultEvents() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        JobManager.View job = jobs.submit("SSE_TEST", Duration.ofSeconds(20), context -> {
            context.log("SSE 第一行");
            context.log("SSE 第二行");
            release.await();
            return Map.of("ok", true);
        });

        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        HttpRequest login = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"" + PASSWORD + "\"}"))
                .build();
        assertEquals(204, client.send(login, HttpResponse.BodyHandlers.ofString()).statusCode(),
                "SSE 测试需要先登录");

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/jobs/" + job.id() + "/events"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"),
                "SSE 响应必须为 text/event-stream");

        StringBuilder body = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    body.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // 任务结束/取消时连接关闭。
            }
        });

        Thread.sleep(300L);
        release.countDown();
        reader.join(10_000L);

        String stream = body.toString();
        assertTrue(stream.contains("event:state"), "缺少 state 事件：\n" + stream);
        assertTrue(stream.contains("event:log"), "缺少 log 事件：\n" + stream);
        assertTrue(stream.contains("event:result"), "缺少 result 事件：\n" + stream);
        assertTrue(stream.contains("SSE 第一行"), "日志内容未通过 SSE 下发：\n" + stream);
        assertTrue(stream.contains("\"ok\":true"), "result 事件缺少任务结果：\n" + stream);
        assertEquals(JobManager.State.SUCCEEDED, jobs.view(job.id()).state());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }
}
