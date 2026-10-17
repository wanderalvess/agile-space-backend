package com.agilespace.backend.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
@Slf4j
public class ShowcaseWebSocketHandler extends TextWebSocketHandler implements PresenceBroadcaster {

    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_LIMIT = 512 * 1024;

    private static final Map<String, Set<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final PresenceChannel presence;

    @Autowired
    public ShowcaseWebSocketHandler(@Autowired(required = false) ObjectMapper objectMapper, PresenceRegistry presenceRegistry) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.presence = new PresenceChannel(presenceRegistry, PresenceModule.SHOWCASE, "sessionId", this.objectMapper);
    }

    public ShowcaseWebSocketHandler(ObjectMapper objectMapper) {
        this(objectMapper, new PresenceRegistry());
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String sessionId = getSessionId(session);
        if (sessionId != null) {
            // O envio por sessão é serializado pelo decorator: o broadcast de presença roda em outra thread
            // (conexão/fechamento/varredura) que os broadcasts vindos de requisições REST.
            WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
            Set<WebSocketSession> sessions = roomSessions.computeIfAbsent(sessionId, k -> new CopyOnWriteArraySet<>());
            sessions.add(safe);
            log.info("Showcase WebSocket connected. Showcase SessionId: {}, WebSocket SessionId: {}, Total connected: {}", 
                     sessionId, session.getId(), sessions.size());
            presence.joined(sessionId, session, safe, () -> sessionsOf(sessionId));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = getSessionId(session);
        if (sessionId != null) {
            // atômico: sem a janela entre isEmpty() e remove() em que uma conexão nova era perdida
            roomSessions.computeIfPresent(sessionId, (k, sessions) -> {
                sessions.removeIf(s -> s.getId().equals(session.getId()));
                return sessions.isEmpty() ? null : sessions;
            });
            log.info("Showcase WebSocket closed. Showcase SessionId: {}, WebSocket SessionId: {}", sessionId, session.getId());
            presence.left(sessionId, session, () -> sessionsOf(sessionId));
        }
    }

    @Override
    public PresenceModule presenceModule() {
        return PresenceModule.SHOWCASE;
    }

    @Override
    public void broadcastPresence(String sessionId, PresenceRegistry.Snapshot snapshot) {
        presence.broadcast(sessionId, snapshot, sessionsOf(sessionId));
    }

    private Set<WebSocketSession> sessionsOf(String sessionId) {
        Set<WebSocketSession> sessions = roomSessions.get(sessionId);
        return sessions == null ? Set.of() : sessions;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.debug("Received client Showcase WebSocket message from session {} ({} chars)", session.getId(), message.getPayloadLength());
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
