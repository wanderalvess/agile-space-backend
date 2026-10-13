package com.agilespace.backend.websocket;

import com.agilespace.backend.domain.RetroCard;
import com.agilespace.backend.service.RetroCardRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RetroWebSocketHandlerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private WebSocketSession session(String id, String boardId, String userId, String role) throws Exception {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        when(s.getUri()).thenReturn(new URI("wss://x/ws/retro/" + boardId + "?token=t"));
        when(s.getAttributes()).thenReturn(Map.of("userId", userId, "role", role));
        return s;
    }

    private JsonNode sent(WebSocketSession s) throws Exception {
        ArgumentCaptor<TextMessage> cap = ArgumentCaptor.forClass(TextMessage.class);
        verify(s).sendMessage(cap.capture());
        return JSON.readTree(cap.getValue().getPayload());
    }

    @Test
    void eachSessionGetsItsOwnRedactedPayload() throws Exception {
        RetroWebSocketHandler handler = new RetroWebSocketHandler(JSON);
        String board = "ws-board-1";
        WebSocketSession author = session("s1", board, "ana", "MEMBER");
        WebSocketSession other = session("s2", board, "bia", "MEMBER");
        WebSocketSession facilitator = session("s3", board, "boss", "MEMBER");
        for (WebSocketSession s : List.of(author, other, facilitator)) handler.afterConnectionEstablished(s);

        RetroCard card = RetroCard.builder().id("c1").boardId(board).columnKey("good").content("texto secreto")
                .authorId("ana").order(1L).votes(new LinkedHashSet<>(Set.of("ana"))).build();
        RetroCardRedactor.Context ctx = new RetroCardRedactor.Context("boss", false, false, Set.of("actions"));

        handler.broadcastCards(board, "CARD_SAVED", card, ctx);

        JsonNode toAuthor = sent(author).get("payload");
        JsonNode toOther = sent(other).get("payload");
        JsonNode toFacilitator = sent(facilitator).get("payload");
        assertEquals("texto secreto", toAuthor.get("content").asText());
        assertEquals("ana", toAuthor.get("authorId").asText());
        assertFalse(toAuthor.get("hidden").asBoolean());

        assertEquals("", toOther.get("content").asText());
        assertTrue(toOther.get("authorId").isNull());
        assertTrue(toOther.get("hidden").asBoolean());
        assertEquals(0, toOther.get("votes").size());
        assertFalse(toOther.toString().contains("texto secreto"));
        assertFalse(toOther.toString().contains("ana"));

        assertEquals("texto secreto", toFacilitator.get("content").asText());
        assertTrue(toFacilitator.get("authorId").isNull());
    }

    @Test
    void listPayloadIsRedactedPerSessionAndRevealedShowsText() throws Exception {
        RetroWebSocketHandler handler = new RetroWebSocketHandler(JSON);
        String board = "ws-board-2";
        WebSocketSession other = session("s9", board, "bia", "MEMBER");
        handler.afterConnectionEstablished(other);
        RetroCard card = RetroCard.builder().id("c1").boardId(board).columnKey("good").content("texto secreto")
                .authorId("ana").order(1L).build();

        handler.broadcastCards(board, "CARDS_IMPORTED", List.of(card), new RetroCardRedactor.Context("boss", false, false, Set.of()));
        JsonNode hidden = sent(other).get("payload").get(0);
        assertTrue(hidden.get("hidden").asBoolean());
        assertEquals("", hidden.get("content").asText());

        clearInvocations(other);
        handler.broadcastCards(board, "CARDS_IMPORTED", List.of(card), new RetroCardRedactor.Context("boss", true, false, Set.of()));
        JsonNode revealed = sent(other).get("payload").get(0);
        assertEquals("texto secreto", revealed.get("content").asText());
        assertTrue(revealed.get("authorId").isNull());
    }
}
