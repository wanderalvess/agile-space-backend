package com.agilespace.backend.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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
import java.util.stream.Collectors;

/**
 * Base dos WebSockets por sala (Brainstorming, Health Check). Cada subclasse tem o seu próprio mapa de
 * salas. O JWT já foi validado no handshake e o userId fica nos atributos da sessão, o que permite
 * entregar um evento só a algumas pessoas (voto do Health Check, que não pode chegar aos demais).
 */
@Slf4j
public abstract class BoardWebSocketHandler extends TextWebSocketHandler {

    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_LIMIT = 512 * 1024;

    private final Map<String, Set<WebSocketSession>> boardSessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    protected BoardWebSocketHandler(ObjectMapper objectMapper) {
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
            log.debug("{} connected. BoardId: {}, SessionId: {}", getClass().getSimpleName(), boardId, session.getId());
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
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // O cliente não envia nada útil por aqui; nunca logar o conteúdo.
        log.debug("Ignored client message from session {}", session.getId());
    }

    public void broadcastEvent(String boardId, String eventType, Object payload) {
        broadcastEventToUsers(boardId, eventType, payload, null);
    }

    public void broadcastRefresh(String boardId) {
        broadcastEvent(boardId, "REFRESH_BOARD", null);
    }

    /** Só às sessões dos usuários informados; {@code userIds == null} = a sala inteira. */
    public void broadcastEventToUsers(String boardId, String eventType, Object payload, Set<String> userIds) {
        Set<WebSocketSession> sessions = boardSessions.get(boardId);
        if (sessions != null && userIds != null) {
            sessions = sessions.stream()
                    .filter(s -> userIds.contains(String.valueOf(s.getAttributes().get("userId"))))
                    .collect(Collectors.toSet());
        }
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> messageMap = new HashMap<>();
            messageMap.put("type", eventType);
            messageMap.put("boardId", boardId);
            messageMap.put("payload", payload);
            messageMap.put("timestamp", System.currentTimeMillis());
            TextMessage textMessage = new TextMessage(objectMapper.writeValueAsString(messageMap));
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    try {
                        session.sendMessage(textMessage);
                    } catch (IOException e) {
                        log.error("Failed to send message to session {}", session.getId(), e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to serialize or broadcast event '{}' for board {}", eventType, boardId, e);
        }
    }

    private String getBoardId(WebSocketSession session) {
        if (session.getUri() == null) return null;
        String path = session.getUri().getPath();
        if (path == null || path.isEmpty()) return null;
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
