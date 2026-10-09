package com.agilespace.backend.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

@Component
@Slf4j
public class PokerWebSocketHandler extends TextWebSocketHandler {

    /** Envio por sessão é serializado pelo decorator: requests concorrentes não podem escrever no mesmo socket ao mesmo tempo. */
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

    // roomId -> (sessionId -> sessão decorada)
    private static final Map<String, Map<String, WebSocketSession>> roomSessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public PokerWebSocketHandler(@Autowired(required = false) ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String roomId = getRoomId(session);
        if (roomId != null) {
            WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES);
            Map<String, WebSocketSession> sessions = roomSessions.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>());
            sessions.put(session.getId(), safe);
            log.info("Poker WebSocket connected. RoomId: {}, SessionId: {}, Total connected in room: {}",
                     roomId, session.getId(), sessions.size());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String roomId = getRoomId(session);
        if (roomId != null) {
            roomSessions.computeIfPresent(roomId, (k, sessions) -> {
                sessions.remove(session.getId());
                return sessions.isEmpty() ? null : sessions;
            });
            log.info("Poker WebSocket closed. RoomId: {}, SessionId: {}", roomId, session.getId());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Conteúdo vindo do cliente não vai para o log (injeção de linhas); só o tamanho.
        log.debug("Received client Poker WebSocket message from session {} ({} chars)", session.getId(), message.getPayloadLength());
    }

    public void broadcastEvent(String roomId, String eventType, Object payload) {
        broadcast(roomId, eventType, payload, s -> true);
    }

    /**
     * Como {@link #broadcastEvent}, mas entrega só às sessões dos usuários informados (o userId vem do
     * JWT, gravado nos atributos pelo JwtHandshakeInterceptor). {@code userIds == null} = sala inteira.
     * Usado nas DMs do chat, cujo texto não pode chegar aos demais participantes.
     */
    public void broadcastEventToUsers(String roomId, String eventType, Object payload, Set<String> userIds) {
        if (userIds == null) {
            broadcastEvent(roomId, eventType, payload);
            return;
        }
        broadcast(roomId, eventType, payload, s -> userIds.contains(String.valueOf(s.getAttributes().get("userId"))));
    }

    /** Entrega a todos da sala, exceto às sessões dos usuários informados (ex.: voto às cegas). */
    public void broadcastEventExcludingUsers(String roomId, String eventType, Object payload, Set<String> excludedUserIds) {
        broadcast(roomId, eventType, payload,
                s -> excludedUserIds == null || !excludedUserIds.contains(String.valueOf(s.getAttributes().get("userId"))));
    }

    private void broadcast(String roomId, String eventType, Object payload, Predicate<WebSocketSession> filter) {
        Map<String, WebSocketSession> all = roomSessions.get(roomId);
        if (all == null || all.isEmpty()) return;
        List<WebSocketSession> targets = new ArrayList<>();
        for (WebSocketSession s : all.values()) {
            if (filter.test(s)) targets.add(s);
        }
        if (targets.isEmpty()) return;
        try {
            Map<String, Object> messageMap = new HashMap<>();
            messageMap.put("type", eventType);
            messageMap.put("roomId", roomId);
            messageMap.put("payload", payload);
            messageMap.put("timestamp", System.currentTimeMillis());

            String json = objectMapper.writeValueAsString(messageMap);
            log.debug("Broadcasting event '{}' to {} sessions on room {}", eventType, targets.size(), roomId);
            broadcastToSessions(targets, new TextMessage(json));
        } catch (Exception e) {
            log.error("Failed to serialize or broadcast event '{}' for room {}", eventType, roomId, e);
        }
    }

    public void broadcastRefresh(String roomId) {
        broadcastEvent(roomId, "REFRESH_ROOM", null);
    }

    public void broadcastReaction(String roomId, String payload) {
        Map<String, WebSocketSession> all = roomSessions.get(roomId);
        if (all != null && !all.isEmpty()) {
            log.debug("Broadcasting REACTION to {} sessions on room {}", all.size(), roomId);
            broadcastToSessions(new ArrayList<>(all.values()), new TextMessage(payload));
        }
    }

    /** Falha em uma sessão (fechada, lenta, buffer cheio) nunca impede a entrega às demais. */
    private void broadcastToSessions(List<WebSocketSession> sessions, TextMessage message) {
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) continue;
            try {
                session.sendMessage(message);
            } catch (Exception e) {
                log.warn("Failed to send message to session {}: {}", session.getId(), e.toString());
            }
        }
    }

    private String getRoomId(WebSocketSession session) {
        if (session.getUri() == null) return null;
        String path = session.getUri().getPath();
        if (path == null || path.isEmpty()) return null;
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
