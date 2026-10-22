package com.hdsl.server.proxy;

import com.hdsl.server.config.PclProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工作台 WebSocket 双向转发（方案 §4.6）：浏览器 ↔ 本应用 ↔ JDK HttpClient WebSocket ↔ 127.0.0.1:实例端口。
 * 只允许由 {@link WorkbenchHandshakeInterceptor} 校验过的目标；本类不做任何外部地址解析。
 */
@Component
public class WorkbenchWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkbenchWebSocketHandler.class);
    private static final long CONNECT_TIMEOUT_SECONDS = 5L;
    private static final int MAX_MESSAGE_BYTES = 1024 * 1024;

    private final PclProperties properties;
    private final HttpClient http;
    private final Map<String, WebSocket> upstreams = new ConcurrentHashMap<>();

    public WorkbenchWebSocketHandler(PclProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.getProxyConnectTimeout() == null
                        ? Duration.ofSeconds(5) : properties.getProxyConnectTimeout())
                .build();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        session.setTextMessageSizeLimit(MAX_MESSAGE_BYTES);
        session.setBinaryMessageSizeLimit(MAX_MESSAGE_BYTES);
        String target = (String) session.getAttributes().get(WorkbenchHandshakeInterceptor.ATTR_UPSTREAM);
        if (target == null) {
            session.close(CloseStatus.SERVER_ERROR);
            return;
        }
        WebSocket.Builder builder = http.newWebSocketBuilder();
        Duration connectTimeout = properties.getProxyConnectTimeout();
        if (connectTimeout != null) {
            builder.connectTimeout(connectTimeout);
        }
        String cookie = (String) session.getAttributes().get(WorkbenchHandshakeInterceptor.ATTR_COOKIE);
        if (cookie != null && !cookie.isBlank()) {
            builder.header("Cookie", cookie);
        }
        String protocols = (String) session.getAttributes().get(WorkbenchHandshakeInterceptor.ATTR_SUBPROTOCOL);
        if (protocols != null && !protocols.isBlank()) {
            String[] values = Arrays.stream(protocols.split(","))
                    .map(String::trim).filter(value -> !value.isEmpty()).toArray(String[]::new);
            if (values.length == 1) {
                builder.subprotocols(values[0]);
            } else if (values.length > 1) {
                builder.subprotocols(values[0], Arrays.copyOfRange(values, 1, values.length));
            }
        }
        CompletableFuture<WebSocket> pending = builder.buildAsync(URI.create(target), new UpstreamListener(session));
        try {
            WebSocket socket = pending.get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            upstreams.put(session.getId(), socket);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            closeSession(session, CloseStatus.SERVER_ERROR);
        } catch (ExecutionException | TimeoutException failure) {
            log.warn("工作台 WebSocket 连接上游失败：{}", failure.getClass().getSimpleName());
            closeSession(session, CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        WebSocket socket = upstreams.get(session.getId());
        if (socket == null) {
            closeSession(session, CloseStatus.SERVER_ERROR);
            return;
        }
        socket.sendText(message.getPayload(), true)
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        closeSession(session, CloseStatus.SERVER_ERROR);
                    }
                });
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        WebSocket socket = upstreams.get(session.getId());
        if (socket == null) {
            closeSession(session, CloseStatus.SERVER_ERROR);
            return;
        }
        socket.sendBinary(message.getPayload().duplicate(), true)
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        closeSession(session, CloseStatus.SERVER_ERROR);
                    }
                });
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        WebSocket socket = upstreams.get(session.getId());
        if (socket != null) {
            socket.sendPong(message.getPayload().duplicate());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        WebSocket socket = upstreams.remove(session.getId());
        if (socket == null || socket.isOutputClosed()) {
            return;
        }
        int code = status == null ? 1000 : status.getCode();
        if (code < 1000 || code > 4999 || code == 1005 || code == 1006) {
            code = 1000;
        }
        socket.sendClose(code, status == null ? null : status.getReason());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        WebSocket socket = upstreams.remove(session.getId());
        if (socket != null) {
            socket.abort();
        }
        closeSession(session, CloseStatus.SERVER_ERROR);
    }

    private static void closeSession(WebSocketSession session, CloseStatus status) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.close(status);
                }
            }
        } catch (IOException ignored) {
            // 对端已断开。
        }
    }

    private void sendToBrowser(WebSocketSession session, WebSocketMessage<?> message) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(message);
                }
            }
        } catch (IOException failure) {
            closeSession(session, CloseStatus.SERVER_ERROR);
        }
    }

    /** JDK WebSocket 监听器：上游 → 浏览器（text/binary/ping/pong/close）。 */
    private final class UpstreamListener implements WebSocket.Listener {

        private final WebSocketSession session;
        private final StringBuilder partialText = new StringBuilder();

        private UpstreamListener(WebSocketSession session) {
            this.session = session;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partialText.append(data);
            if (last) {
                String payload = partialText.toString();
                partialText.setLength(0);
                sendToBrowser(session, new TextMessage(payload));
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            if (last) {
                sendToBrowser(session, new BinaryMessage(bytes));
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            sendToBrowser(session, new PingMessage(message.duplicate()));
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            sendToBrowser(session, new PongMessage(message.duplicate()));
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            int code = statusCode >= 1000 && statusCode <= 4999 && statusCode != 1005 && statusCode != 1006
                    ? statusCode : 1000;
            closeSession(session, new CloseStatus(code, reason == null ? null : truncate(reason)));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.debug("工作台上游 WebSocket 异常：{}", error.getClass().getSimpleName());
            closeSession(session, CloseStatus.SERVER_ERROR);
        }

        private String truncate(String reason) {
            byte[] bytes = reason.getBytes(StandardCharsets.UTF_8);
            if (bytes.length <= 120) {
                return reason;
            }
            return new String(Arrays.copyOf(bytes, 120), StandardCharsets.UTF_8);
        }
    }
}
