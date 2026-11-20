package com.agilespace.backend.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class BrainstormingWebSocketHandler extends BoardWebSocketHandler {

    @Autowired
    public BrainstormingWebSocketHandler(@Autowired(required = false) ObjectMapper objectMapper, PresenceRegistry presenceRegistry) {
        super(objectMapper, presenceRegistry, PresenceModule.BRAINSTORMING);
    }

    public BrainstormingWebSocketHandler(ObjectMapper objectMapper) {
        this(objectMapper, new PresenceRegistry());
    }
}
