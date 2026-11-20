package com.agilespace.backend.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Cola entre um handler de sala e o {@link PresenceRegistry}: registra/remove a sessão e entrega o
 * {@code PRESENCE_UPDATED} (docs/presence-api.md) SÓ às sessões que pediram {@code ?presence=1}. O frontend antigo trata
 * qualquer tipo desconhecido como "recarrega a sala", então o evento novo nunca vai a quem não pediu.
 *
 * <p>Falha de presença nunca atrapalha a conexão nem o fechamento: tudo aqui é protegido por try/catch.
 */
@Slf4j
final class PresenceChannel {

    static final String EVENT_TYPE = "PRESENCE_UPDATED";

    private final PresenceRegistry registry;
    private final PresenceModule module;
    /** Nome da chave da sala no envelope do handler: {@code roomId}, {@code boardId} ou {@code sessionId}. */
    private final String roomKey;
    private final ObjectMapper objectMapper;

    PresenceChannel(PresenceRegistry registry, PresenceModule module, String roomKey, ObjectMapper objectMapper) {
        this.registry = registry;
        this.module = module;
        this.roomKey = roomKey;
        this.objectMapper = objectMapper;
    }

    /**
     * Chamar DEPOIS de a sessão entrar no mapa de sessões da sala. {@code safe} é a sessão decorada (a que recebe os
     * envios); {@code roomSessions} lê as sessões da sala no momento do envio.
     */
    void joined(String roomId, WebSocketSession raw, WebSocketSession safe, Supplier<? extends Collection<WebSocketSession>> roomSessions) {
        try {
            String userId = userIdOf(raw);
            if (userId == null) return;
            PresenceRegistry.Change change = registry.join(module, roomId, userId, raw.getId(), raw::isOpen);
            if (change.changed()) {
                broadcast(roomId, change.snapshot(), roomSessions.get());
            } else if (wantsPresence(raw)) {
                // outra aba de quem já estava online: a lista não mudou, mas esta sessão precisa do estado inicial
                send(safe, roomId, change.snapshot());
            }
        } catch (RuntimeException e) {
            log.warn("Presence join failed for module {}: {}", module.path(), e.toString());
        }
    }

    /** Chamar DEPOIS de a sessão sair do mapa de sessões da sala. */
    void left(String roomId, WebSocketSession raw, Supplier<? extends Collection<WebSocketSession>> roomSessions) {
        try {
            String userId = userIdOf(raw);
            if (userId == null) return;
            PresenceRegistry.Change change = registry.leave(module, roomId, userId, raw.getId());
            if (change.changed()) {
                broadcast(roomId, change.snapshot(), roomSessions.get());
            }
        } catch (RuntimeException e) {
            log.warn("Presence leave failed for module {}: {}", module.path(), e.toString());
        }
    }

    /** Entrega o estado às sessões da sala com opt-in. */
    void broadcast(String roomId, PresenceRegistry.Snapshot snapshot, Collection<WebSocketSession> sessions) {
        if (sessions == null || sessions.isEmpty()) return;
        TextMessage message = null;
        for (WebSocketSession s : sessions) {
            if (!s.isOpen() || !wantsPresence(s)) continue;
            try {
                if (message == null) message = toMessage(roomId, snapshot);
                s.sendMessage(message);
            } catch (Exception e) {
                log.warn("Failed to send presence to session {}: {}", s.getId(), e.toString());
            }
        }
    }

    private void send(WebSocketSession session, String roomId, PresenceRegistry.Snapshot snapshot) {
        if (!session.isOpen()) return;
        try {
            session.sendMessage(toMessage(roomId, snapshot));
        } catch (Exception e) {
            log.warn("Failed to send presence snapshot to session {}: {}", session.getId(), e.toString());
        }
    }

    private TextMessage toMessage(String roomId, PresenceRegistry.Snapshot snapshot) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("online", snapshot.online());
        payload.put("total", snapshot.total());
        payload.put("version", snapshot.version());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", EVENT_TYPE);
        envelope.put(roomKey, roomId);
        envelope.put("payload", payload);
        envelope.put("timestamp", System.currentTimeMillis());
        return new TextMessage(objectMapper.writeValueAsString(envelope));
    }

    /** {@code ?presence=1} ou {@code ?presence=true} na URL da conexão. */
    static boolean wantsPresence(WebSocketSession session) {
        try {
            if (session.getUri() == null) return false;
            String v = UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams().getFirst("presence");
            return "1".equals(v) || "true".equalsIgnoreCase(v);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** O userId vem dos atributos gravados pelo JwtHandshakeInterceptor (token validado), nunca da URL nem de mensagem do cliente. */
    static String userIdOf(WebSocketSession session) {
        Object id = session.getAttributes().get("userId");
        return id == null || id.toString().isBlank() ? null : id.toString();
    }
}
