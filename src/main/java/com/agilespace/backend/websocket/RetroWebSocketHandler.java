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
public class RetroWebSocketHandler extends TextWebSocketHandler {

    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_LIMIT = 512 * 1024;
    private static final Map<String, Set<WebSocketSession>> boardSessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public RetroWebSocketHandler(@Autowired(required = false) ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String boardId = getBoardId(session);
        if (boardId != null) {
            // sendMessage concorrente na mesma sessão (broadcasts de threads de requisição diferentes)
            // corrompe o frame; o decorator serializa os envios e limita o buffer de um cliente lento.
            WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
            boardSessions.computeIfAbsent(boardId, k -> new CopyOnWriteArraySet<>()).add(safe);
            log.debug("Retro WebSocket connected. BoardId: {}, SessionId: {}", boardId, session.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String boardId = getBoardId(session);
        if (boardId != null) {
            // atômico: sem a janela entre isEmpty() e remove() em que uma conexão nova era perdida
            boardSessions.computeIfPresent(boardId, (k, sessions) -> {
                sessions.removeIf(s -> s.getId().equals(session.getId()));
                return sessions.isEmpty() ? null : sessions;
            });
            log.debug("Retro WebSocket closed. BoardId: {}, SessionId: {}", boardId, session.getId());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // O cliente não envia nada útil por aqui; nunca logar o conteúdo.
        log.debug("Ignored client Retro WebSocket message from session {}", session.getId());
    }

    public void broadcastEvent(String boardId, String eventType, Object payload) {
        broadcastEventToUsers(boardId, eventType, payload, null);
    }

    /**
     * Como {@link #broadcastEvent}, mas entrega só às sessões dos usuários informados
     * (o userId vem do JWT, gravado nos atributos pelo JwtHandshakeInterceptor).
     * {@code userIds == null} significa o board inteiro. Usado nas DMs do chat, cujo
     * texto não pode chegar aos demais participantes.
     */
    public void broadcastEventToUsers(String boardId, String eventType, Object payload, Set<String> userIds) {
        Set<WebSocketSession> sessions = boardSessions.get(boardId);
        if (sessions != null && userIds != null) {
            sessions = sessions.stream()
                    .filter(s -> userIds.contains(String.valueOf(s.getAttributes().get("userId"))))
                    .collect(java.util.stream.Collectors.toSet());
        }
        if (sessions != null && !sessions.isEmpty()) {
            try {
                Map<String, Object> messageMap = new HashMap<>();
                messageMap.put("type", eventType);
                messageMap.put("boardId", boardId);
                messageMap.put("payload", payload);
                messageMap.put("timestamp", System.currentTimeMillis());

                String json = objectMapper.writeValueAsString(messageMap);
                TextMessage textMessage = new TextMessage(json);
                log.debug("Broadcasting event '{}' to {} sessions on board {}", eventType, sessions.size(), boardId);
                broadcastToSessions(sessions, textMessage);
            } catch (Exception e) {
                log.error("Failed to serialize or broadcast event '{}' for board {}", eventType, boardId, e);
            }
        }
    }

    public void broadcastRefresh(String boardId) {
        broadcastEvent(boardId, "REFRESH_BOARD", null);
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

    private String getBoardId(WebSocketSession session) {
        if (session.getUri() == null) return null;
        String path = session.getUri().getPath();
        if (path == null || path.isEmpty()) return null;
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
