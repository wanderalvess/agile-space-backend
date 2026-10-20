package com.agilespace.backend.websocket;

import com.agilespace.backend.security.SprintPlanHandshakeInterceptor;
import com.agilespace.backend.domain.SprintPlan;
import com.agilespace.backend.repository.SprintPlanRepository;
import com.agilespace.backend.service.SquadAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("SprintPlanWebSocketHandler e handshake - REFRESH_PLAN por plano")
class SprintPlanWebSocketHandlerTest {

    private WebSocketSession session(String id, String planId) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.getUri()).thenReturn(URI.create("ws://localhost/ws/sprint-plan/" + planId + "?token=x"));
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    @Test
    @DisplayName("broadcastRefresh manda REFRESH_PLAN só para as sessões do plano")
    void refreshOnlyToPlanSessions() throws Exception {
        SprintPlanWebSocketHandler handler = new SprintPlanWebSocketHandler(new ObjectMapper());
        WebSocketSession a = session("a", "P1");
        WebSocketSession b = session("b", "P1");
        WebSocketSession other = session("c", "P2");
        handler.afterConnectionEstablished(a);
        handler.afterConnectionEstablished(b);
        handler.afterConnectionEstablished(other);

        handler.broadcastRefresh("P1");

        ArgumentCaptor<TextMessage> cap = ArgumentCaptor.forClass(TextMessage.class);
        verify(a).sendMessage(cap.capture());
        verify(b).sendMessage(any(TextMessage.class));
        verify(other, never()).sendMessage(any(TextMessage.class));
        JsonNode json = new ObjectMapper().readTree(cap.getValue().getPayload());
        assertEquals("REFRESH_PLAN", json.get("type").asText());
        assertEquals("P1", json.get("planId").asText());
        assertEquals("P1", json.get("boardId").asText());
    }

    @Test
    @DisplayName("sessão fechada sai da sala e não recebe mais")
    void closedSessionIsRemoved() throws Exception {
        SprintPlanWebSocketHandler handler = new SprintPlanWebSocketHandler(new ObjectMapper());
        WebSocketSession a = session("a", "P1");
        handler.afterConnectionEstablished(a);
        handler.afterConnectionClosed(a, CloseStatus.NORMAL);
        handler.broadcastRefresh("P1");
        verify(a, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("handshake: plano da squad onde a pessoa tem acesso entra; sem acesso ou plano inexistente leva 403")
    void handshake() {
        SprintPlanRepository repo = mock(SprintPlanRepository.class);
        SquadAccessService access = mock(SquadAccessService.class);
        SprintPlan plan = SprintPlan.builder().id("P1").squadId("SQ1").build();
        when(repo.findById("P1")).thenReturn(Optional.of(plan));
        when(repo.findById("P9")).thenReturn(Optional.empty());
        when(access.matchesSquad("SQ1", "u1", "MEMBER")).thenReturn(true);
        when(access.matchesSquad("SQ1", "u2", "MEMBER")).thenReturn(false);
        SprintPlanHandshakeInterceptor interceptor = new SprintPlanHandshakeInterceptor(repo, access);

        assertTrue(handshake(interceptor, "P1", "u1").ok());
        assertFalse(handshake(interceptor, "P1", "u2").ok());
        assertFalse(handshake(interceptor, "P9", "u1").ok());
        assertEquals(HttpStatus.FORBIDDEN, handshake(interceptor, "P1", "u2").status());
    }

    private record Result(boolean ok, HttpStatus status) {}

    private Result handshake(SprintPlanHandshakeInterceptor interceptor, String planId, String userId) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ws/sprint-plan/" + planId);
        req.setRequestURI("/ws/sprint-plan/" + planId);
        ServerHttpResponse resp = mock(ServerHttpResponse.class);
        Map<String, Object> attrs = new HashMap<>(Map.of("userId", userId, "role", "MEMBER"));
        boolean ok = interceptor.beforeHandshake(new ServletServerHttpRequest(req), resp, null, attrs);
        ArgumentCaptor<HttpStatus> st = ArgumentCaptor.forClass(HttpStatus.class);
        if (!ok) verify(resp).setStatusCode(st.capture());
        return new Result(ok, ok ? null : st.getValue());
    }
}
