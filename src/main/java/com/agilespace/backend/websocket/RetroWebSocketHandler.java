package com.agilespace.backend.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.JsonNode;
import com.agilespace.backend.domain.RetroCard;
import com.agilespace.backend.service.RetroCardRedactor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@Component
@Slf4j
public class RetroWebSocketHandler extends TextWebSocketHandler implements PresenceBroadcaster {

    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_LIMIT = 512 * 1024;
    // Tolerância para reconexão (o cliente reconecta em 5s) antes de considerar alguém ausente.
    private static final long ABSENCE_GRACE_MS = 45_000;
    // "boardId|userId" -> instante da última desconexão (limpa ao reconectar e por varredura lazy)
    private static final Map<String, Long> lastDisconnect = new ConcurrentHashMap<>();
    private static final Map<String, Set<WebSocketSession>> boardSessions = new ConcurrentHashMap<>();

    // "Escrevendo…" (docs/ceremony-radar-retro-api.md, 2.4): efêmero, só em memória.
    static final long TYPING_MIN_INTERVAL_MS = 2_000;
    static final long TYPING_EXPIRES_MS = 5_000;
    static final int TYPING_MAX_MESSAGE_CHARS = 512;
    private static final int TYPING_MAX_COLUMN_KEY = 50;
    private static final int TYPING_MAX_CHANNEL_ID = 100;

    private final ObjectMapper objectMapper;
    private final PresenceChannel presence;
    private final Supplier<RetroTypingGate> typingGate;
    private final LongSupplier clock;
    // sessionId -> instante do último TYPING aceito (limite de taxa por sessão); removido ao fechar a conexão
    private final Map<String, Long> lastTyping = new ConcurrentHashMap<>();

    @Autowired
    public RetroWebSocketHandler(@Autowired(required = false) ObjectMapper objectMapper, PresenceRegistry presenceRegistry,
                                 ObjectProvider<RetroTypingGate> typingGate) {
        this(objectMapper, presenceRegistry, typingGate == null ? () -> null : typingGate::getIfAvailable, System::currentTimeMillis);
    }

    RetroWebSocketHandler(ObjectMapper objectMapper, PresenceRegistry presenceRegistry, Supplier<RetroTypingGate> typingGate, LongSupplier clock) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.presence = new PresenceChannel(presenceRegistry, PresenceModule.RETRO, "boardId", this.objectMapper);
        this.typingGate = typingGate;
        this.clock = clock;
    }

    public RetroWebSocketHandler(ObjectMapper objectMapper, PresenceRegistry presenceRegistry) {
        this(objectMapper, presenceRegistry, () -> null, System::currentTimeMillis);
    }

    public RetroWebSocketHandler(ObjectMapper objectMapper) {
        this(objectMapper, new PresenceRegistry());
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String boardId = getBoardId(session);
        if (boardId != null) {
            // sendMessage concorrente na mesma sessão (broadcasts de threads de requisição diferentes)
            // corrompe o frame; o decorator serializa os envios e limita o buffer de um cliente lento.
            WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
            Object uid = session.getAttributes().get("userId");
            if (uid != null) lastDisconnect.remove(boardId + "|" + uid);
            boardSessions.computeIfAbsent(boardId, k -> new CopyOnWriteArraySet<>()).add(safe);
            log.debug("Retro WebSocket connected. BoardId: {}, SessionId: {}", boardId, session.getId());
            presence.joined(boardId, session, safe, () -> sessionsOf(boardId));
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
            lastTyping.remove(session.getId());
            Object uid = session.getAttributes().get("userId");
            if (uid != null) {
                long now = System.currentTimeMillis();
                lastDisconnect.values().removeIf(t -> now - t > ABSENCE_GRACE_MS);
                lastDisconnect.put(boardId + "|" + uid, now);
            }
            log.debug("Retro WebSocket closed. BoardId: {}, SessionId: {}", boardId, session.getId());
            presence.left(boardId, session, () -> sessionsOf(boardId));
        }
    }

    @Override
    public PresenceModule presenceModule() {
        return PresenceModule.RETRO;
    }

    @Override
    public void broadcastPresence(String boardId, PresenceRegistry.Snapshot snapshot) {
        presence.broadcast(boardId, snapshot, sessionsOf(boardId));
    }

    private Set<WebSocketSession> sessionsOf(String boardId) {
        Set<WebSocketSession> sessions = boardSessions.get(boardId);
        return sessions == null ? Set.of() : sessions;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Único tipo aceito do cliente: TYPING, e só de quem abriu a conexão com ?typing=1. Nunca logar o conteúdo.
        if (!WsOptIn.wants(session, WsOptIn.TYPING)) {
            log.debug("Ignored client Retro WebSocket message from session {}", session.getId());
            return;
        }
        try {
            relayTyping(session, message.getPayload());
        } catch (Exception e) {
            log.debug("Ignored invalid TYPING from session {}: {}", session.getId(), e.toString());
        }
    }

    /**
     * Repassa o "escrevendo…" à sala. O remetente é SEMPRE o userId do token do handshake (campos do corpo além de
     * type/scope/columnKey/channelId são ignorados); no máximo 1 evento a cada 2 s por sessão (o excedente some em
     * silêncio); só sessões da sala com ?typing=1 recebem, menos o próprio remetente.
     */
    private void relayTyping(WebSocketSession session, String raw) throws Exception {
        if (raw == null || raw.length() > TYPING_MAX_MESSAGE_CHARS) return;
        JsonNode in = objectMapper.readTree(raw);
        if (in == null || !in.isObject() || !"TYPING".equals(in.path("type").asText(null))) return;
        String userId = PresenceChannel.userIdOf(session);
        String boardId = getBoardId(session);
        if (userId == null || boardId == null) return;

        String scope = in.hasNonNull("scope") ? in.get("scope").asText() : "card";
        if (!"card".equals(scope) && !"chat".equals(scope)) return;
        String columnKey = shortText(in.get("columnKey"), TYPING_MAX_COLUMN_KEY);
        String channelId = shortText(in.get("channelId"), TYPING_MAX_CHANNEL_ID);
        if ("chat".equals(scope) && channelId == null) return;

        long now = clock.getAsLong();
        Long last = lastTyping.get(session.getId());
        if (last != null && now - last < TYPING_MIN_INTERVAL_MS) return;
        lastTyping.put(session.getId(), now);

        RetroTypingGate gate = typingGate.get();
        if (gate == null) return;
        Object role = session.getAttributes().get("role");
        Optional<RetroTypingGate.Route> route = gate.route(boardId, userId, role == null ? null : role.toString(), scope, channelId);
        if (route.isEmpty()) return;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", route.get().anonymous() ? null : userId);
        payload.put("anonymous", route.get().anonymous());
        payload.put("scope", scope);
        if ("card".equals(scope) && columnKey != null) payload.put("columnKey", columnKey);
        if ("chat".equals(scope)) payload.put("channelId", channelId);
        payload.put("expiresInMs", TYPING_EXPIRES_MS);
        payload.put("expiresAt", now + TYPING_EXPIRES_MS);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "TYPING");
        envelope.put("boardId", boardId);
        envelope.put("payload", payload);
        envelope.put("timestamp", now);
        TextMessage out = new TextMessage(objectMapper.writeValueAsString(envelope));

        Set<String> audience = route.get().audience();
        for (WebSocketSession target : sessionsOf(boardId)) {
            String targetUser = PresenceChannel.userIdOf(target);
            if (!target.isOpen() || targetUser == null || targetUser.equals(userId)
                    || !WsOptIn.wants(target, WsOptIn.TYPING)
                    || (audience != null && !audience.contains(targetUser))) {
                continue;
            }
            try {
                target.sendMessage(out);
            } catch (IOException e) {
                log.debug("Failed to send TYPING to session {}", target.getId());
            }
        }
    }

    private static String shortText(JsonNode node, int max) {
        if (node == null || !node.isTextual()) return null;
        String text = node.asText().strip();
        return text.isEmpty() || text.length() > max ? null : text;
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

    /**
     * O usuário está presente no board: tem sessão aberta ou desconectou há menos que a tolerância
     * de reconexão. Usado para decidir se o facilitador está ausente ("assumir controle").
     */
    public boolean isUserPresent(String boardId, String userId) {
        if (boardId == null || userId == null) return false;
        Set<WebSocketSession> sessions = boardSessions.get(boardId);
        if (sessions != null && sessions.stream().anyMatch(s -> s.isOpen()
                && userId.equals(String.valueOf(s.getAttributes().get("userId"))))) {
            return true;
        }
        Long left = lastDisconnect.get(boardId + "|" + userId);
        return left != null && System.currentTimeMillis() - left <= ABSENCE_GRACE_MS;
    }

    /**
     * Eventos que carregam card(s) ({@link RetroCard} ou lista): o payload é montado POR SESSÃO, pela
     * redação do anonimato (quem não é autor não recebe o texto de card oculto; ver
     * {@link RetroCardRedactor}). O userId e o papel de cada sessão vêm do JWT do handshake.
     */
    public void broadcastCards(String boardId, String eventType, Object cardOrCards, RetroCardRedactor.Context ctx) {
        Set<WebSocketSession> sessions = boardSessions.get(boardId);
        if (sessions == null || sessions.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) continue;
            try {
                Object uid = session.getAttributes().get("userId");
                Object role = session.getAttributes().get("role");
                String viewerId = uid == null ? null : uid.toString();
                boolean admin = role != null && "ADMIN".equalsIgnoreCase(role.toString());
                Object payload;
                if (cardOrCards instanceof RetroCard card) {
                    payload = RetroCardRedactor.view(card, ctx, viewerId, admin);
                } else if (cardOrCards instanceof List<?> list) {
                    List<RetroCard> cards = new java.util.ArrayList<>();
                    for (Object o : list) cards.add((RetroCard) o);
                    payload = RetroCardRedactor.view(cards, ctx, viewerId, admin);
                } else {
                    continue;
                }
                Map<String, Object> messageMap = new HashMap<>();
                messageMap.put("type", eventType);
                messageMap.put("boardId", boardId);
                messageMap.put("payload", payload);
                messageMap.put("timestamp", now);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(messageMap)));
            } catch (Exception e) {
                log.error("Failed to send card event '{}' to session {} on board {}", eventType, session.getId(), boardId, e);
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
