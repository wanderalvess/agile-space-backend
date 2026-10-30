package com.agilespace.backend.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Sala em tempo real do Sprint Planner: /ws/sprint-plan/{planId}. Só emite o sinal {@code REFRESH_PLAN} (o cliente
 * refaz o GET), no mesmo formato dos outros handlers de sala. Sem presença. O handshake (JWT + acesso à squad do plano)
 * é do {@code SprintPlanHandshakeInterceptor}.
 */
@Component
@Slf4j
public class SprintPlanWebSocketHandler extends TextWebSocketHandler {

    public static final String REFRESH_PLAN = "REFRESH_PLAN";
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_LIMIT = 512 * 1024;

    private final Map<String, Set<WebSocketSession>> planSessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    @Autowired
    public SprintPlanWebSocketHandler(@Autowired(required = false) ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String planId = planId(session);
        if (planId == null) return;
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
        planSessions.computeIfAbsent(planId, k -> new CopyOnWriteArraySet<>()).add(safe);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String planId = planId(session);
        if (planId == null) return;
        planSessions.computeIfPresent(planId, (k, sessions) -> {
            sessions.removeIf(s -> s.getId().equals(session.getId()));
            return sessions.isEmpty() ? null : sessions;
        });
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.debug("Ignored client message from session {}", session.getId());
    }

    public void broadcastRefresh(String planId) {
        broadcastEvent(planId, REFRESH_PLAN);
    }

    public void broadcastEvent(String planId, String eventType) {
        Set<WebSocketSession> sessions = planSessions.get(planId);
        if (sessions == null || sessions.isEmpty()) return;
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", eventType);
            msg.put("planId", planId);
            msg.put("boardId", planId);
            msg.put("payload", null);
            msg.put("timestamp", System.currentTimeMillis());
            TextMessage text = new TextMessage(objectMapper.writeValueAsString(msg));
            for (WebSocketSession s : sessions) {
                if (!s.isOpen()) continue;
                try {
                    s.sendMessage(text);
                } catch (IOException e) {
                    log.error("Failed to send message to session {}", s.getId(), e);
                }
            }
        } catch (Exception e) {
            log.error("Failed to broadcast {} for plan {}", eventType, planId, e);
        }
    }

    private static String planId(WebSocketSession session) {
        if (session.getUri() == null) return null;
        String path = session.getUri().getPath();
        if (path == null || path.isEmpty()) return null;
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
