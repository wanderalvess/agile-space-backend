package com.agilespace.backend.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class BrainstormingWebSocketHandler extends BoardWebSocketHandler {

    public BrainstormingWebSocketHandler(@Autowired(required = false) ObjectMapper objectMapper) {
        super(objectMapper);
    }
}
