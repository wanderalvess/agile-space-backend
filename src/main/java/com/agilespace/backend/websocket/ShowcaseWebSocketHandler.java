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
public class ShowcaseWebSocketHandler extends TextWebSocketHandler {

    private static final Map<String, Set<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public ShowcaseWebSocketHandler(@Autowired(required = false) ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String sessionId = getSessionId(session);
        if (sessionId != null) {
            roomSessions.computeIfAbsent(sessionId, k -> new CopyOnWriteArraySet<>()).add(session);
            log.info("Showcase WebSocket connected. Showcase SessionId: {}, WebSocket SessionId: {}, Total connected: {}", 
                     sessionId, session.getId(), roomSessions.get(sessionId).size());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = getSessionId(session);
        if (sessionId != null && roomSessions.containsKey(sessionId)) {
            Set<WebSocketSession> sessions = roomSessions.get(sessionId);
            sessions.remove(session);
            if (sessions.isEmpty()) {
                roomSessions.remove(sessionId);
            }
            log.info("Showcase WebSocket closed. Showcase SessionId: {}, WebSocket SessionId: {}", sessionId, session.getId());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.info("Received client Showcase WebSocket message from session {}: {}", session.getId(), message.getPayload());
    }

    public void broadcastEvent(String sessionId, String eventType, Object payload) {
        Set<WebSocketSession> sessions = roomSessions.get(sessionId);
        if (sessions != null && !sessions.isEmpty()) {
            try {
                Map<String, Object> messageMap = new HashMap<>();
                messageMap.put("type", eventType);
                messageMap.put("sessionId", sessionId);
                messageMap.put("payload", payload);
                messageMap.put("timestamp", System.currentTimeMillis());

                String json = objectMapper.writeValueAsString(messageMap);
                TextMessage textMessage = new TextMessage(json);
                log.info("Broadcasting event '{}' to {} sessions in showcase session {}", eventType, sessions.size(), sessionId);
                broadcastToSessions(sessions, textMessage);
            } catch (Exception e) {
                log.error("Failed to serialize or broadcast event '{}' for showcase session {}", eventType, sessionId, e);
            }
        }
    }

    public void broadcastRefresh(String sessionId) {
        broadcastEvent(sessionId, "REFRESH_SESSION", null);
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

    private String getSessionId(WebSocketSession session) {
        if (session.getUri() == null) return null;
        String path = session.getUri().getPath();
        if (path == null || path.isEmpty()) return null;
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
