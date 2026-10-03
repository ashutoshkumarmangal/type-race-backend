package com.typerush.game;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typerush.protocol.ClientCommand;
import com.typerush.protocol.server.Payloads;

/**
 * WebSocket entry point at {@code /ws/game}.
 *
 * <p>Client messages are {@code {"type": "...", ...}}; server replies are {@code {"type": "...", "data": {...}}}.
 * Handled types: {@code join}, {@code join_quick}, {@code join_room}, {@code ready}, {@code start},
 * {@code progress}, {@code finish}, {@code rematch}, {@code leave}, {@code ping}.
 */
@Component
public class GameSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GameSocketHandler.class);

    private final ObjectMapper objectMapper;
    private final MessageSender sender;
    private final RoomManager roomManager;

    public GameSocketHandler(ObjectMapper objectMapper, MessageSender sender, RoomManager roomManager) {
        this.objectMapper = objectMapper;
        this.sender = sender;
        this.roomManager = roomManager;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sender.register(session);
        log.debug("socket open: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Optional<PlayerSlot> slotRef = roomManager.player(session.getId());
        if (slotRef.isEmpty()) {
            ClientCommand raw = parse(message.getPayload());
            roomManager.onConnect(session, raw == null ? null : raw.nickname());
            if (raw != null && raw.type() != null && !"ping".equals(raw.type()) && !"hello".equals(raw.type())) {
                route(session.getId(), raw);
            }
            return;
        }

        ClientCommand command = parse(message.getPayload());
        if (command == null || command.type() == null) {
            return;
        }
        if (command.type().equals("ping")) {
            sender.send(session.getId(), "pong", System.currentTimeMillis());
            return;
        }
        route(session.getId(), command);
    }

    private void route(String sessionId, ClientCommand command) {
        Optional<PlayerSlot> slotRef = roomManager.player(sessionId);
        if (slotRef.isEmpty()) {
            return;
        }
        PlayerSlot slot = slotRef.get();
        try {
            switch (command.type()) {
                case "join" -> roomManager.joinRoom(slot, command.roomCode());
                case "join_quick" -> roomManager.joinQuickMatch(slot);
                case "join_room" -> roomManager.joinRoom(slot, command.roomCode());
                case "nickname" -> roomManager.rename(slot, command.nickname());
                case "ready" -> roomManager.setReady(slot, Boolean.TRUE.equals(command.ready()));
                case "start" -> roomManager.forceStart(slot);
                case "progress" -> roomManager.onProgress(slot, command);
                case "finish" -> roomManager.onFinish(slot, command);
                case "rematch" -> roomManager.voteRematch(slot, Boolean.TRUE.equals(command.again()));
                case "leave" -> roomManager.leaveRoom(slot, "left");
                default -> sender.send(sessionId, "error",
                        new Payloads.Error("unknown_type", "Unsupported command: " + command.type()));
            }
        } catch (RuntimeException ex) {
            log.error("Command '{}' failed for {}", command.type(), slot.getNickname(), ex);
            sender.send(sessionId, "error", new Payloads.Error("server_error", "Something went wrong handling "
                    + command.type()));
        }
    }

    private ClientCommand parse(String payload) {
        try {
            return objectMapper.readValue(payload, ClientCommand.class);
        } catch (Exception ex) {
            log.debug("Cannot parse incoming payload: {}", payload);
            return null;
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        roomManager.onDisconnect(session.getId());
        log.debug("socket closed: {} ({})", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("socket transport error for {}", session.getId());
        roomManager.onDisconnect(session.getId());
        sender.closeQuietly(session);
    }
}