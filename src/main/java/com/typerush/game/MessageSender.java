package com.typerush.game;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typerush.protocol.Envelope;

/**
 * Owns every outbound socket write. Sessions are wrapped in a {@link ConcurrentWebSocketSessionDecorator}
 * so a broadcast tick and an individual acknowledgement can be in flight at the same time
 * without corrupting frames.
 */
@Component
public class MessageSender {

    private static final Logger log = LoggerFactory.getLogger(MessageSender.class);

    private final int sendTimeoutMs = 5_000;
    private final int bufferLimitBytes = 256 * 1024;

    private final ObjectMapper objectMapper;
    private final Map<String, WebSocketSession> registry = new ConcurrentHashMap<>();

    public MessageSender(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(WebSocketSession raw) {
        registry.put(raw.getId(), new ConcurrentWebSocketSessionDecorator(raw, sendTimeoutMs, bufferLimitBytes));
    }

    public void unregister(String sessionId) {
        registry.remove(sessionId);
    }

    public WebSocketSession session(String sessionId) {
        return registry.get(sessionId);
    }

    public boolean isOpen(String sessionId) {
        WebSocketSession session = registry.get(sessionId);
        return session != null && session.isOpen();
    }

    public void send(String sessionId, String type, Object data) {
        WebSocketSession session = registry.get(sessionId);
        if (session == null) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(write(type, data)));
        } catch (IOException | IllegalStateException ex) {
            log.warn("Dropping session {}: send failed ({})", sessionId, ex.toString());
            closeQuietly(session);
        }
    }

    public void broadcast(Iterable<PlayerSlot> players, String type, Object data) {
        String payload = write(type, data);
        for (PlayerSlot player : players) {
            if (!player.isConnected()) {
                continue;
            }
            WebSocketSession session = registry.get(player.getSession().getId());
            if (session == null || !session.isOpen()) {
                continue;
            }
            try {
                session.sendMessage(new TextMessage(payload));
            } catch (IOException | IllegalStateException ex) {
                log.warn("Broadcast to {} failed: {}", player.getNickname(), ex.toString());
            }
        }
    }

    public void closeQuietly(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.SERVER_ERROR);
            }
        } catch (IOException ignored) {
            // nothing useful to do while tearing down
        }
    }

    private String write(String type, Object data) {
        try {
            return objectMapper.writeValueAsString(Envelope.of(type, data));
        } catch (JsonProcessingException ex) {
            log.error("Cannot serialize outgoing message '{}'", type, ex);
            throw new IllegalStateException("message serialization failed: " + type, ex);
        }
    }
}