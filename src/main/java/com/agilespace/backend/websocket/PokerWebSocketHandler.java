package com.agilespace.backend.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
@Slf4j
public class PokerWebSocketHandler extends TextWebSocketHandler {

    private static final Map<String, Set<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public PokerWebSocketHandler(@Autowired(required = false) ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String roomId = getRoomId(session);
        if (roomId != null) {
            roomSessions.computeIfAbsent(roomId, k -> new CopyOnWriteArraySet<>()).add(session);
            log.info("Poker WebSocket connected. RoomId: {}, SessionId: {}, Total connected in room: {}", 
                     roomId, session.getId(), roomSessions.get(roomId).size());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String roomId = getRoomId(session);
        if (roomId != null && roomSessions.containsKey(roomId)) {
            Set<WebSocketSession> sessions = roomSessions.get(roomId);
            sessions.remove(session);
            if (sessions.isEmpty()) {
                roomSessions.remove(roomId);
            }
            log.info("Poker WebSocket closed. RoomId: {}, SessionId: {}", roomId, session.getId());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.info("Received client Poker WebSocket message from session {}: {}", session.getId(), message.getPayload());
    }

    public void broadcastEvent(String roomId, String eventType, Object payload) {
        Set<WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions != null && !sessions.isEmpty()) {
            try {
                Map<String, Object> messageMap = new HashMap<>();
                messageMap.put("type", eventType);
                messageMap.put("roomId", roomId);
                messageMap.put("payload", payload);
                messageMap.put("timestamp", System.currentTimeMillis());

                String json = objectMapper.writeValueAsString(messageMap);
                TextMessage textMessage = new TextMessage(json);
                log.info("Broadcasting event '{}' to {} sessions on room {}", eventType, sessions.size(), roomId);
                broadcastToSessions(sessions, textMessage);
            } catch (Exception e) {
                log.error("Failed to serialize or broadcast event '{}' for room {}", eventType, roomId, e);
            }
        }
    }

    public void broadcastRefresh(String roomId) {
        broadcastEvent(roomId, "REFRESH_ROOM", null);
    }

    public void broadcastReaction(String roomId, String payload) {
        Set<WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions != null && !sessions.isEmpty()) {
            TextMessage reactionMessage = new TextMessage(payload);
            log.info("Broadcasting REACTION to {} sessions on room {}", sessions.size(), roomId);
            broadcastToSessions(sessions, reactionMessage);
        }
    }

    private void broadcastToSessions(Set<WebSocketSession> sessions, TextMessage message) {
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(message);
                } catch (IOException e) {
                    log.error("Failed to send message to session {}", session.getId(), e);
                }
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
