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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Canal do usuário (/ws/user?token=...): um socket por aba, indexado pelo userId do JWT (gravado pelo
 * JwtHandshakeInterceptor). Só manda SINAIS ({type, conversationId?}); o cliente refaz o GET correspondente,
 * como o REFRESH_BOARD das salas. Nada de conteúdo (texto de mensagem, aviso) trafega por aqui.
 */
@Component
@Slf4j
public class UserWebSocketHandler extends TextWebSocketHandler {

    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_LIMIT = 64 * 1024;

    /** userId -> (sessionId -> sessão decorada) */
    private final Map<String, Map<String, WebSocketSession>> userSessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public UserWebSocketHandler(@Autowired(required = false) ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String userId = userIdOf(session);
        if (userId == null) {
            closeQuietly(session);
            return;
        }
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
        userSessions.computeIfAbsent(userId, k -> new ConcurrentHashMap<>()).put(session.getId(), safe);
        log.debug("User channel connected. SessionId: {}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String userId = userIdOf(session);
        if (userId == null) return;
        userSessions.computeIfPresent(userId, (k, sessions) -> {
            sessions.remove(session.getId());
            return sessions.isEmpty() ? null : sessions;
        });
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Canal só de saída; conteúdo do cliente nunca vai para o log.
        log.debug("Ignored client message on user channel {} ({} chars)", session.getId(), message.getPayloadLength());
    }

    /** Envia o sinal a todas as abas abertas dos usuários informados. */
    public void sendSignal(Collection<String> userIds, String type, String conversationId) {
        if (userIds == null || userIds.isEmpty() || type == null) return;
        String json;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", type);
            if (conversationId != null) payload.put("conversationId", conversationId);
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("Failed to serialize user signal {}", type, e);
            return;
        }
        TextMessage message = new TextMessage(json);
        for (String userId : userIds) {
            Map<String, WebSocketSession> sessions = userId == null ? null : userSessions.get(userId);
            if (sessions == null) continue;
            for (WebSocketSession s : sessions.values()) {
                if (!s.isOpen()) continue;
                try {
                    s.sendMessage(message);
                } catch (IOException | RuntimeException e) {
                    log.warn("Failed to send user signal to session {}", s.getId());
                }
            }
        }
    }

    /** Todos os usuários conectados agora (aviso para "todos"). */
    public Collection<String> connectedUserIds() {
        return userSessions.keySet();
    }

    int sessionCount(String userId) {
        Map<String, WebSocketSession> sessions = userSessions.get(userId);
        return sessions == null ? 0 : sessions.size();
    }

    private static String userIdOf(WebSocketSession session) {
        Object id = session.getAttributes().get("userId");
        return id == null || id.toString().isBlank() ? null : id.toString();
    }

    private static void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException ignored) {
            // nada a fazer
        }
    }
}
