package com.agilespace.backend.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Eventos novos da Review só para quem abriu o WebSocket com {@code ?presence=1}; o evento que o frontend antigo conhece
 * (SESSION_UPDATED) continua indo a todos pelo caminho de sempre.
 */
class ShowcaseAudienceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ShowcaseWebSocketHandler handler;
    private String room;

    @BeforeEach
    void setUp() {
        handler = new ShowcaseWebSocketHandler(JSON, new PresenceRegistry());
        room = "audience-" + UUID.randomUUID();
    }

    private WebSocketSession connect(String id, String userId, String query) throws Exception {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        when(s.getUri()).thenReturn(new URI("wss://x/ws/showcase/" + room + "?token=t" + query));
        when(s.getAttributes()).thenReturn(Map.of("userId", userId, "role", "MEMBER"));
        handler.afterConnectionEstablished(s);
        return s;
    }

    private List<String> typesReceived(WebSocketSession s) throws Exception {
        ArgumentCaptor<TextMessage> cap = ArgumentCaptor.forClass(TextMessage.class);
        verify(s, atLeast(0)).sendMessage(cap.capture());
        List<String> types = new ArrayList<>();
        for (TextMessage m : cap.getAllValues()) {
            types.add(JSON.readTree(m.getPayload()).get("type").asText());
        }
        return types;
    }

    @Test
    void optInEventsReachOnlyOptedInConnections() throws Exception {
        WebSocketSession novo = connect("s1", "ana", "&presence=1");
        WebSocketSession antigo = connect("s2", "bia", "");

        handler.broadcastToOptIn(room, "PRESENTER_UPDATED", Map.of("presenter", Map.of("userId", "ana")));
        handler.broadcastToOptIn(room, "CARD_UPDATED", Map.of("cardId", "c1"));

        assertTrue(typesReceived(novo).containsAll(List.of("PRESENTER_UPDATED", "CARD_UPDATED")));
        assertFalse(typesReceived(antigo).contains("PRESENTER_UPDATED"));
        assertFalse(typesReceived(antigo).contains("CARD_UPDATED"));
        assertEquals(List.of(), typesReceived(antigo), "o cliente antigo não recebe NADA novo (nem a presença)");
    }

    @Test
    void legacyEventGoesOnlyToConnectionsWithoutOptIn() throws Exception {
        WebSocketSession novo = connect("s1", "ana", "&presence=1");
        WebSocketSession antigo = connect("s2", "bia", "");

        handler.broadcastToLegacy(room, "SESSION_UPDATED", Map.of("id", room));

        assertEquals(List.of("SESSION_UPDATED"), typesReceived(antigo));
        assertFalse(typesReceived(novo).contains("SESSION_UPDATED"));
    }

    @Test
    void plainBroadcastStillReachesEveryone() throws Exception {
        WebSocketSession novo = connect("s1", "ana", "&presence=1");
        WebSocketSession antigo = connect("s2", "bia", "");

        handler.broadcastEvent(room, "SESSION_UPDATED", Map.of("id", room));

        assertTrue(typesReceived(novo).contains("SESSION_UPDATED"));
        assertEquals(List.of("SESSION_UPDATED"), typesReceived(antigo));
    }

    @Test
    void envelopeHasTheSameShapeAsTheOtherReviewEvents() throws Exception {
        WebSocketSession novo = connect("s1", "ana", "&presence=true");

        handler.broadcastToOptIn(room, "PRESENTER_UPDATED", Map.of("presenter", Map.of("userId", "ana")));

        ArgumentCaptor<TextMessage> cap = ArgumentCaptor.forClass(TextMessage.class);
        verify(novo, atLeast(1)).sendMessage(cap.capture());
        JsonNode n = JSON.readTree(cap.getAllValues().get(cap.getAllValues().size() - 1).getPayload());
        assertEquals("PRESENTER_UPDATED", n.get("type").asText());
        assertEquals(room, n.get("sessionId").asText());
        assertEquals("ana", n.get("payload").get("presenter").get("userId").asText());
        assertTrue(n.has("timestamp"));
    }

    @Test
    void hasLegacyConnectionsSeesOnlyOpenConnectionsWithoutOptIn() throws Exception {
        assertFalse(handler.hasLegacyConnections(room));
        WebSocketSession novo = connect("s1", "ana", "&presence=1");
        assertFalse(handler.hasLegacyConnections(room));
        WebSocketSession antigo = connect("s2", "bia", "");
        assertTrue(handler.hasLegacyConnections(room));

        when(antigo.isOpen()).thenReturn(false);
        assertFalse(handler.hasLegacyConnections(room));
        assertNotNull(novo);
    }
}
