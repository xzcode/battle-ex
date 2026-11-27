package com.battleex.web;

import com.battleex.platform.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** First message authenticates; never put tokens in URL or logs. Full snapshots simplify reconnect. */
@Component
public final class LobbySocket extends TextWebSocketHandler {
    private final Platform platform;
    private final ObjectMapper json;
    private final Map<String, Client> clients = new ConcurrentHashMap<>();

    private static final class Client {
        final WebSocketSession socket;
        final long acceptedAt = System.nanoTime();
        String token;
        Platform.Lobby last;
        Client(WebSocketSession socket) {
            this.socket = new ConcurrentWebSocketSessionDecorator(socket, 5000, 512 * 1024);
        }
    }
    public LobbySocket(Platform platform, ObjectMapper json) { this.platform = platform; this.json = json; }

    @Override public void afterConnectionEstablished(WebSocketSession socket) {
        socket.setTextMessageSizeLimit(2048);
        clients.put(socket.getId(), new Client(socket));
    }
    @Override protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
        Client c = clients.get(socket.getId());
        if (c == null) return;
        synchronized (c) {
            try {
                if (c.token != null || System.nanoTime()-c.acceptedAt > 5_000_000_000L) {
                    close(c, CloseStatus.POLICY_VIOLATION); return;
                }
                var node = json.readTree(message.getPayload());
                if (!"authenticate".equals(node.path("type").asText()) || !node.path("token").isTextual()) {
                    close(c, CloseStatus.POLICY_VIOLATION); return;
                }
                String token = node.get("token").asText();
                platform.authenticate(token);
                c.token = token;
                send(c, platform.lobby(token));
            } catch (Exception e) { close(c, CloseStatus.POLICY_VIOLATION); }
        }
    }
    @Scheduled(fixedDelay=1000)
    public void tick() {
        platform.sweep();
        for (Client c : clients.values()) {
            synchronized (c) {
                if (!c.socket.isOpen()) { clients.remove(c.socket.getId(), c); continue; }
                if (c.token == null) {
                    if (System.nanoTime()-c.acceptedAt > 5_000_000_000L) close(c,CloseStatus.POLICY_VIOLATION);
                    continue;
                }
                try {
                    var snapshot = platform.lobby(c.token);
                    if (!snapshot.equals(c.last)) send(c, snapshot);
                } catch (Platform.Unauthorized e) {
                    close(c,CloseStatus.POLICY_VIOLATION);
                } catch (Exception e) {
                    close(c,CloseStatus.SERVER_ERROR);
                }
            }
        }
    }
    private void send(Client c, Platform.Lobby snapshot) throws IOException {
        c.socket.sendMessage(new TextMessage(json.writeValueAsString(Map.of("type","lobby","data",snapshot))));
        c.last = snapshot;
    }
    private void close(Client c, CloseStatus status) {
        clients.remove(c.socket.getId(), c);
        try { c.socket.close(status); } catch (IOException ignored) {}
    }
    @Override public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        clients.remove(socket.getId());
    }
    @Override public void handleTransportError(WebSocketSession socket, Throwable error) {
        Client c = clients.remove(socket.getId());
        if (c != null) close(c, CloseStatus.SERVER_ERROR);
    }
}
