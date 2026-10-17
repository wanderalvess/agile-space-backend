package com.agilespace.backend.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class HealthCheckWebSocketHandler extends BoardWebSocketHandler {

    @Autowired
    public HealthCheckWebSocketHandler(@Autowired(required = false) ObjectMapper objectMapper, PresenceRegistry presenceRegistry) {
        super(objectMapper, presenceRegistry, PresenceModule.HEALTH_CHECK);
    }

    public HealthCheckWebSocketHandler(ObjectMapper objectMapper) {
        this(objectMapper, new PresenceRegistry());
    }

    /** Etapa da apresentação: só para as sessões que abriram o socket com {@code ?presentation=1}. */
    public void broadcastPresentationStage(String boardId, Object payload) {
        broadcastEventToSessions(boardId, "PRESENTATION_STAGE", payload, s -> WsOptIn.wants(s, WsOptIn.PRESENTATION));
    }
}
