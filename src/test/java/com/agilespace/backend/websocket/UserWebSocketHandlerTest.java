package com.agilespace.backend.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class UserWebSocketHandlerTest {

    private static WebSocketSession session(String id, String userId) {
        WebSocketSession s = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        if (userId != null) attrs.put("userId", userId);
        when(s.getAttributes()).thenReturn(attrs);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    @Test
    void signalReachesOnlyTargetUserSessions() throws Exception {
        UserWebSocketHandler handler = new UserWebSocketHandler(null);
        WebSocketSession ana1 = session("s1", "ana");
        WebSocketSession ana2 = session("s2", "ana");
        WebSocketSession bob = session("s3", "bob");
        handler.afterConnectionEstablished(ana1);
        handler.afterConnectionEstablished(ana2);
        handler.afterConnectionEstablished(bob);

        handler.sendSignal(List.of("ana"), "MESSAGE", "c1");

        verify(ana1).sendMessage(argThat(m -> ((TextMessage) m).getPayload().contains("\"conversationId\":\"c1\"")));
        verify(ana2).sendMessage(any());
        verify(bob, never()).sendMessage(any());
    }

    @Test
    void signalWithoutConversationOmitsField() throws Exception {
        UserWebSocketHandler handler = new UserWebSocketHandler(null);
        WebSocketSession ana = session("s1", "ana");
        handler.afterConnectionEstablished(ana);

        handler.sendSignal(List.of("ana"), "NOTIFICATION", null);

        verify(ana).sendMessage(argThat(m -> ((TextMessage) m).getPayload().equals("{\"type\":\"NOTIFICATION\"}")));
    }

    @Test
    void closeRemovesSessionAndAnonymousIsRejected() throws Exception {
        UserWebSocketHandler handler = new UserWebSocketHandler(null);
        WebSocketSession ana = session("s1", "ana");
        handler.afterConnectionEstablished(ana);
        assertEquals(1, handler.sessionCount("ana"));
        handler.afterConnectionClosed(ana, CloseStatus.NORMAL);
        assertEquals(0, handler.sessionCount("ana"));
        assertTrue(handler.connectedUserIds().isEmpty());

        WebSocketSession anon = session("s9", null);
        handler.afterConnectionEstablished(anon);
        verify(anon).close(CloseStatus.POLICY_VIOLATION);
    }
}
