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
public class BrainstormingWebSocketHandler extends TextWebSocketHandler {

    private static final Map<String, Set<WebSocketSession>> boardSessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public BrainstormingWebSocketHandler(@Autowired(required = false) ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String boardId = getBoardId(session);
        if (boardId != null) {
            boardSessions.computeIfAbsent(boardId, k -> new CopyOnWriteArraySet<>()).add(session);
            log.info("Brainstorming WebSocket connected. BoardId: {}, SessionId: {}, Total connected in board: {}", 
                     boardId, session.getId(), boardSessions.get(boardId).size());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String boardId = getBoardId(session);
        if (boardId != null && boardSessions.containsKey(boardId)) {
            Set<WebSocketSession> sessions = boardSessions.get(boardId);
            sessions.remove(session);
            if (sessions.isEmpty()) {
                boardSessions.remove(boardId);
            }
            log.info("Brainstorming WebSocket closed. BoardId: {}, SessionId: {}", boardId, session.getId());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.info("Received client Brainstorming WebSocket message from session {}: {}", session.getId(), message.getPayload());
    }

    public void broadcastEvent(String boardId, String eventType, Object payload) {
        Set<WebSocketSession> sessions = boardSessions.get(boardId);
        if (sessions != null && !sessions.isEmpty()) {
            try {
                Map<String, Object> messageMap = new HashMap<>();
                messageMap.put("type", eventType);
                messageMap.put("boardId", boardId);
                messageMap.put("payload", payload);
                messageMap.put("timestamp", System.currentTimeMillis());

                String json = objectMapper.writeValueAsString(messageMap);
                TextMessage textMessage = new TextMessage(json);
                log.info("Broadcasting event '{}' to {} sessions on Brainstorming board {}", eventType, sessions.size(), boardId);
                broadcastToSessions(sessions, textMessage);
            } catch (Exception e) {
                log.error("Failed to serialize or broadcast event '{}' for Brainstorming board {}", eventType, boardId, e);
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
