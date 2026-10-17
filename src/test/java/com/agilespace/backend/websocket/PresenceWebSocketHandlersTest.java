package com.agilespace.backend.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Presença nos 5 handlers de sala: entrar/sair gera o broadcast certo, só para quem pediu {@code ?presence=1}. */
class PresenceWebSocketHandlersTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PresenceRegistry registry;
    private String room;

    @BeforeEach
    void setUp() {
        registry = new PresenceRegistry();
        // as salas de Poker/Retro/Showcase são estáticas: um id único por teste evita contaminação entre testes
        room = "presence-" + UUID.randomUUID();
    }

    private TextWebSocketHandler handler(PresenceModule module) {
        return switch (module) {
            case POKER -> new PokerWebSocketHandler(JSON, registry);
            case RETRO -> new RetroWebSocketHandler(JSON, registry);
            case BRAINSTORMING -> new BrainstormingWebSocketHandler(JSON, registry);
            case HEALTH_CHECK -> new HealthCheckWebSocketHandler(JSON, registry);
            case SHOWCASE -> new ShowcaseWebSocketHandler(JSON, registry);
        };
    }

    private static String roomKey(PresenceModule module) {
        return switch (module) {
            case POKER -> "roomId";
            case RETRO, BRAINSTORMING, HEALTH_CHECK -> "boardId";
            case SHOWCASE -> "sessionId";
        };
    }

    private WebSocketSession session(PresenceModule module, String id, String userId, String query) throws Exception {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        when(s.getUri()).thenReturn(new URI("wss://x/ws/" + module.path() + "/" + room + "?token=t" + query));
        when(s.getAttributes()).thenReturn(Map.of("userId", userId, "role", "MEMBER"));
        return s;
    }

    /** Todos os PRESENCE_UPDATED que a sessão recebeu até agora. */
    private List<JsonNode> presenceEvents(WebSocketSession s) throws Exception {
        ArgumentCaptor<TextMessage> cap = ArgumentCaptor.forClass(TextMessage.class);
        verify(s, atLeast(0)).sendMessage(cap.capture());
        List<JsonNode> out = new ArrayList<>();
        for (TextMessage m : cap.getAllValues()) {
            JsonNode n = JSON.readTree(m.getPayload());
            if ("PRESENCE_UPDATED".equals(n.get("type").asText())) out.add(n);
        }
        return out;
    }

    private List<String> online(JsonNode event) {
        List<String> ids = new ArrayList<>();
        event.get("payload").get("online").forEach(n -> ids.add(n.asText()));
        return ids;
    }

    @ParameterizedTest
    @EnumSource(PresenceModule.class)
    void optedInSessionGetsInitialSnapshotWithItself(PresenceModule module) throws Exception {
        TextWebSocketHandler h = handler(module);
        WebSocketSession ana = session(module, "s1", "ana", "&presence=1");

        h.afterConnectionEstablished(ana);

        List<JsonNode> events = presenceEvents(ana);
        assertEquals(1, events.size());
        JsonNode e = events.get(0);
        assertEquals(room, e.get(roomKey(module)).asText());
        assertEquals(List.of("ana"), online(e));
        assertEquals(1, e.get("payload").get("total").asInt());
        assertTrue(e.get("payload").get("version").asLong() > 0);
        assertTrue(e.has("timestamp"));
    }

    @ParameterizedTest
    @EnumSource(PresenceModule.class)
    void joinAndLeaveBroadcastToOptedInSessionsOnly(PresenceModule module) throws Exception {
        TextWebSocketHandler h = handler(module);
        WebSocketSession ana = session(module, "s1", "ana", "&presence=1");
        WebSocketSession legacy = session(module, "s2", "legacy", "");
        WebSocketSession bia = session(module, "s3", "bia", "&presence=true");
        h.afterConnectionEstablished(ana);
        h.afterConnectionEstablished(legacy);
        h.afterConnectionEstablished(bia);

        // ana (com opt-in) recebe o snapshot inicial e a entrada de cada um; legacy (sem opt-in) também conta como presença
        List<JsonNode> anaEvents = presenceEvents(ana);
        assertEquals(List.of("ana"), online(anaEvents.get(0)));
        assertEquals(List.of("ana", "legacy"), online(anaEvents.get(1)));
        assertEquals(List.of("ana", "legacy", "bia"), online(anaEvents.get(2)));
        assertEquals(3, anaEvents.size());
        // a versão só cresce
        assertTrue(anaEvents.get(0).get("payload").get("version").asLong()
                < anaEvents.get(2).get("payload").get("version").asLong());

        // quem não pediu NUNCA recebe o evento novo (o frontend antigo recarregaria a sala)
        assertTrue(presenceEvents(legacy).isEmpty());
        verify(legacy, never()).sendMessage(any());

        h.afterConnectionClosed(bia, CloseStatus.NORMAL);

        anaEvents = presenceEvents(ana);
        assertEquals(4, anaEvents.size());
        assertEquals(List.of("ana", "legacy"), online(anaEvents.get(3)));
        assertEquals(2, anaEvents.get(3).get("payload").get("total").asInt());
        verify(legacy, never()).sendMessage(any());

        h.afterConnectionClosed(ana, CloseStatus.NORMAL);
        h.afterConnectionClosed(legacy, CloseStatus.NORMAL);
        assertEquals(0, registry.roomCount());
    }

    @ParameterizedTest
    @EnumSource(PresenceModule.class)
    void secondTabOfSameUserOnlyGetsItsOwnSnapshot(PresenceModule module) throws Exception {
        TextWebSocketHandler h = handler(module);
        WebSocketSession ana1 = session(module, "s1", "ana", "&presence=1");
        WebSocketSession bia = session(module, "s2", "bia", "&presence=1");
        WebSocketSession ana2 = session(module, "s3", "ana", "&presence=1");
        h.afterConnectionEstablished(ana1);
        h.afterConnectionEstablished(bia);
        int biaBefore = presenceEvents(bia).size();

        h.afterConnectionEstablished(ana2);

        assertEquals(biaBefore, presenceEvents(bia).size(), "a lista não mudou: ninguém mais é avisado");
        List<JsonNode> own = presenceEvents(ana2);
        assertEquals(1, own.size());
        assertEquals(List.of("ana", "bia"), online(own.get(0)));

        // fechar uma das duas abas também não muda nada
        int biaAfterJoin = presenceEvents(bia).size();
        h.afterConnectionClosed(ana1, CloseStatus.NORMAL);
        assertEquals(biaAfterJoin, presenceEvents(bia).size());
        assertEquals(List.of("ana", "bia"), registry.snapshot(module, room).online());

        // a última aba sai: bia é avisada
        h.afterConnectionClosed(ana2, CloseStatus.NORMAL);
        List<JsonNode> biaEvents = presenceEvents(bia);
        assertEquals(List.of("bia"), online(biaEvents.get(biaEvents.size() - 1)));
    }

    @ParameterizedTest
    @EnumSource(PresenceModule.class)
    void presenceBelongsToTokenUserNotToAnythingInTheUrl(PresenceModule module) throws Exception {
        TextWebSocketHandler h = handler(module);
        WebSocketSession ana = session(module, "s1", "ana", "&presence=1&userId=intruso&uid=intruso");

        h.afterConnectionEstablished(ana);

        assertEquals(List.of("ana"), online(presenceEvents(ana).get(0)));
        assertEquals(List.of("ana"), registry.snapshot(module, room).online());
    }

    @ParameterizedTest
    @EnumSource(PresenceModule.class)
    void presenceValueOtherThanOneOrTrueIsNotOptIn(PresenceModule module) throws Exception {
        TextWebSocketHandler h = handler(module);
        WebSocketSession a = session(module, "s1", "ana", "&presence=0");
        WebSocketSession b = session(module, "s2", "bia", "&presence=");
        WebSocketSession c = session(module, "s3", "caio", "&presence=yes");

        h.afterConnectionEstablished(a);
        h.afterConnectionEstablished(b);
        h.afterConnectionEstablished(c);

        for (WebSocketSession s : List.of(a, b, c)) verify(s, never()).sendMessage(any());
        assertEquals(List.of("ana", "bia", "caio"), registry.snapshot(module, room).online());
    }

    @ParameterizedTest
    @EnumSource(PresenceModule.class)
    void existingEventsAreUntouchedAndStillReachEveryone(PresenceModule module) throws Exception {
        TextWebSocketHandler h = handler(module);
        WebSocketSession legacy = session(module, "s1", "legacy", "");
        h.afterConnectionEstablished(legacy);

        switch (module) {
            case POKER -> ((PokerWebSocketHandler) h).broadcastRefresh(room);
            case RETRO -> ((RetroWebSocketHandler) h).broadcastRefresh(room);
            case BRAINSTORMING, HEALTH_CHECK -> ((BoardWebSocketHandler) h).broadcastRefresh(room);
            case SHOWCASE -> ((ShowcaseWebSocketHandler) h).broadcastRefresh(room);
        }

        ArgumentCaptor<TextMessage> cap = ArgumentCaptor.forClass(TextMessage.class);
        verify(legacy, times(1)).sendMessage(cap.capture());
        String type = JSON.readTree(cap.getValue().getPayload()).get("type").asText();
        assertTrue(type.startsWith("REFRESH_"), type);
    }

    @ParameterizedTest
    @EnumSource(PresenceModule.class)
    void presenceDoesNotRegisterWhenThereIsNoUserId(PresenceModule module) throws Exception {
        TextWebSocketHandler h = handler(module);
        WebSocketSession anon = mock(WebSocketSession.class);
        when(anon.getId()).thenReturn("anon");
        when(anon.isOpen()).thenReturn(true);
        when(anon.getUri()).thenReturn(new URI("wss://x/ws/" + module.path() + "/" + room + "?presence=1"));
        when(anon.getAttributes()).thenReturn(Map.of());

        h.afterConnectionEstablished(anon);
        h.afterConnectionClosed(anon, CloseStatus.NORMAL);

        assertEquals(0, registry.roomCount());
        verify(anon, never()).sendMessage(any());
    }

    @ParameterizedTest
    @EnumSource(PresenceModule.class)
    void aFailingSendNeverBreaksTheConnectionOrOtherSessions(PresenceModule module) throws Exception {
        TextWebSocketHandler h = handler(module);
        WebSocketSession broken = session(module, "s1", "ana", "&presence=1");
        doThrow(new java.io.IOException("socket quebrado")).when(broken).sendMessage(any());
        WebSocketSession bia = session(module, "s2", "bia", "&presence=1");

        assertDoesNotThrow(() -> h.afterConnectionEstablished(broken));
        assertDoesNotThrow(() -> h.afterConnectionEstablished(bia));

        List<JsonNode> events = presenceEvents(bia);
        assertEquals(List.of("ana", "bia"), online(events.get(events.size() - 1)));
        assertDoesNotThrow(() -> h.afterConnectionClosed(broken, CloseStatus.SERVER_ERROR));
    }

    @Test
    void handlersExposeTheirModuleForTheSweeper() {
        assertEquals(PresenceModule.POKER, new PokerWebSocketHandler(JSON).presenceModule());
        assertEquals(PresenceModule.RETRO, new RetroWebSocketHandler(JSON).presenceModule());
        assertEquals(PresenceModule.BRAINSTORMING, new BrainstormingWebSocketHandler(JSON).presenceModule());
        assertEquals(PresenceModule.HEALTH_CHECK, new HealthCheckWebSocketHandler(JSON).presenceModule());
        assertEquals(PresenceModule.SHOWCASE, new ShowcaseWebSocketHandler(JSON).presenceModule());
    }

    @Test
    void sweeperTellsTheRoomWhenAZombieSessionIsDropped() throws Exception {
        PokerWebSocketHandler h = new PokerWebSocketHandler(JSON, registry);
        WebSocketSession ana = session(PresenceModule.POKER, "s1", "ana", "&presence=1");
        WebSocketSession zombie = session(PresenceModule.POKER, "s2", "bia", "&presence=1");
        h.afterConnectionEstablished(ana);
        h.afterConnectionEstablished(zombie);
        when(zombie.isOpen()).thenReturn(false); // o servidor já considera fechada, mas o close callback nunca veio

        new PresenceSweeper(registry, List.of(h)).sweep();

        List<JsonNode> events = presenceEvents(ana);
        assertEquals(List.of("ana"), online(events.get(events.size() - 1)));
        assertEquals(List.of("ana"), registry.snapshot(PresenceModule.POKER, room).online());
    }
}
