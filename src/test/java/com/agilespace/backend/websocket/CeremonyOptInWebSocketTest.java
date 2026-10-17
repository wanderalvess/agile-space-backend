package com.agilespace.backend.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Eventos novos só para quem fez opt-in: PRESENTATION_STAGE (Radar) e TYPING (Retro). */
@DisplayName("WebSocket das cerimônias - opt-in de PRESENTATION_STAGE e TYPING")
class CeremonyOptInWebSocketTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static WebSocketSession session(String id, String path, String query, String userId) throws Exception {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        when(s.getUri()).thenReturn(new URI("wss://x" + path + "?token=t" + query));
        when(s.getAttributes()).thenReturn(Map.of("userId", userId, "role", "MEMBER"));
        return s;
    }

    private static List<JsonNode> sent(WebSocketSession s) throws Exception {
        ArgumentCaptor<TextMessage> cap = ArgumentCaptor.forClass(TextMessage.class);
        verify(s, atLeast(0)).sendMessage(cap.capture());
        return cap.getAllValues().stream().map(m -> {
            try {
                return JSON.readTree(m.getPayload());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    private static List<JsonNode> ofType(WebSocketSession s, String type) throws Exception {
        return sent(s).stream().filter(n -> type.equals(n.get("type").asText())).toList();
    }

    // ------------------------------------------------------------------ Radar

    @Test
    @DisplayName("PRESENTATION_STAGE chega só a quem abriu com ?presentation=1 (true também vale); os demais não recebem nada")
    void presentationStageIsOptIn() throws Exception {
        HealthCheckWebSocketHandler handler = new HealthCheckWebSocketHandler(JSON);
        String board = "hc-ws-1";
        WebSocketSession optIn = session("a", "/ws/health-check/" + board, "&presentation=1", "bia");
        WebSocketSession optInTrue = session("b", "/ws/health-check/" + board, "&presentation=TRUE", "caio");
        WebSocketSession old = session("c", "/ws/health-check/" + board, "", "dani");
        WebSocketSession presenceOnly = session("d", "/ws/health-check/" + board, "&presence=1", "edu");
        WebSocketSession other = session("e", "/ws/health-check/" + board, "&presentation=0", "fabi");
        for (WebSocketSession s : List.of(optIn, optInTrue, old, presenceOnly, other)) handler.afterConnectionEstablished(s);
        // o snapshot de presença do ?presence=1 não é o que está em teste
        clearInvocations(optIn, optInTrue, old, presenceOnly, other);

        handler.broadcastPresentationStage(board, Map.of("stage", "radar"));

        for (WebSocketSession s : List.of(optIn, optInTrue)) {
            JsonNode event = ofType(s, "PRESENTATION_STAGE").get(0);
            assertEquals(board, event.get("boardId").asText());
            assertEquals("radar", event.get("payload").get("stage").asText());
        }
        for (WebSocketSession s : List.of(old, presenceOnly, other)) {
            verify(s, never()).sendMessage(any());
        }
    }

    @Test
    @DisplayName("Mudar a etapa não mexe em sessões de outro quadro")
    void otherBoardsAreUntouched() throws Exception {
        HealthCheckWebSocketHandler handler = new HealthCheckWebSocketHandler(JSON);
        WebSocketSession here = session("a", "/ws/health-check/hc-ws-2", "&presentation=1", "bia");
        WebSocketSession elsewhere = session("b", "/ws/health-check/hc-ws-3", "&presentation=1", "caio");
        handler.afterConnectionEstablished(here);
        handler.afterConnectionEstablished(elsewhere);
        clearInvocations(here, elsewhere);

        handler.broadcastPresentationStage("hc-ws-2", Map.of("stage", "plan"));

        assertEquals(1, ofType(here, "PRESENTATION_STAGE").size());
        verify(elsewhere, never()).sendMessage(any());
    }

    // ------------------------------------------------------------------ Retro TYPING

    private static final class Clock {
        final AtomicLong now = new AtomicLong(1_000_000);
    }

    private static RetroWebSocketHandler retro(Clock clock, AtomicReference<RetroTypingGate> gate) {
        return new RetroWebSocketHandler(JSON, new PresenceRegistry(), gate::get, clock.now::get);
    }

    private static RetroTypingGate allowAll() {
        return (boardId, userId, role, scope, channelId) -> Optional.of(new RetroTypingGate.Route(null, false));
    }

    private static void typing(RetroWebSocketHandler h, WebSocketSession from, String json) {
        h.handleTextMessage(from, new TextMessage(json));
    }

    private static WebSocketSession retroSession(String id, String board, String query, String userId) throws Exception {
        return session(id, "/ws/retro/" + board, query, userId);
    }

    @Test
    @DisplayName("TYPING vai às sessões da sala com ?typing=1, menos ao remetente; o userId é o do TOKEN, nunca o do corpo")
    void typingRelaysToOptedInWithTokenIdentity() throws Exception {
        Clock clock = new Clock();
        RetroWebSocketHandler h = retro(clock, new AtomicReference<>(allowAll()));
        String board = "rt-ws-1";
        WebSocketSession ana = retroSession("1", board, "&typing=1", "ana");
        WebSocketSession ana2 = retroSession("2", board, "&typing=1", "ana");
        WebSocketSession bia = retroSession("3", board, "&typing=1", "bia");
        WebSocketSession caio = retroSession("4", board, "", "caio"); // frontend antigo
        for (WebSocketSession s : List.of(ana, ana2, bia, caio)) h.afterConnectionEstablished(s);
        clearInvocations(ana, ana2, bia, caio);

        typing(h, ana, "{\"type\":\"TYPING\",\"scope\":\"card\",\"columnKey\":\"good\",\"userId\":\"vitima\"}");

        JsonNode event = ofType(bia, "TYPING").get(0);
        assertEquals(board, event.get("boardId").asText());
        JsonNode payload = event.get("payload");
        assertEquals("ana", payload.get("userId").asText(), "o userId vem do token, não do corpo");
        assertFalse(payload.get("anonymous").asBoolean());
        assertEquals("card", payload.get("scope").asText());
        assertEquals("good", payload.get("columnKey").asText());
        assertEquals(5000, payload.get("expiresInMs").asLong());
        assertEquals(event.get("timestamp").asLong() + 5000, payload.get("expiresAt").asLong());
        assertFalse(event.toString().contains("vitima"));
        verify(ana, never()).sendMessage(any());
        verify(ana2, never()).sendMessage(any());
        verify(caio, never()).sendMessage(any());
    }

    @Test
    @DisplayName("Sem ?typing=1 a sessão não consegue enviar TYPING (a mensagem é ignorada)")
    void senderWithoutOptInIsIgnored() throws Exception {
        Clock clock = new Clock();
        RetroTypingGate gate = mock(RetroTypingGate.class);
        RetroWebSocketHandler h = retro(clock, new AtomicReference<>(gate));
        String board = "rt-ws-2";
        WebSocketSession oldClient = retroSession("1", board, "", "ana");
        WebSocketSession bia = retroSession("2", board, "&typing=1", "bia");
        h.afterConnectionEstablished(oldClient);
        h.afterConnectionEstablished(bia);
        clearInvocations(oldClient, bia);

        typing(h, oldClient, "{\"type\":\"TYPING\",\"scope\":\"card\"}");

        verify(bia, never()).sendMessage(any());
        verifyNoInteractions(gate);
    }

    @Test
    @DisplayName("Limite de taxa: 1 evento a cada 2 s por sessão; passou o intervalo, volta a valer; o excedente some em silêncio")
    void rateLimitPerSession() throws Exception {
        Clock clock = new Clock();
        RetroWebSocketHandler h = retro(clock, new AtomicReference<>(allowAll()));
        String board = "rt-ws-3";
        WebSocketSession ana = retroSession("1", board, "&typing=1", "ana");
        WebSocketSession caio = retroSession("2", board, "&typing=1", "caio");
        WebSocketSession bia = retroSession("3", board, "&typing=1", "bia");
        for (WebSocketSession s : List.of(ana, caio, bia)) h.afterConnectionEstablished(s);
        clearInvocations(ana, caio, bia);
        String msg = "{\"type\":\"TYPING\",\"scope\":\"card\"}";

        typing(h, ana, msg);
        clock.now.addAndGet(500);
        typing(h, ana, msg);
        clock.now.addAndGet(1_499);
        typing(h, ana, msg);
        assertEquals(1, ofType(bia, "TYPING").size(), "dentro dos 2 s só o primeiro passa");

        clock.now.addAndGet(1);
        typing(h, ana, msg);
        assertEquals(2, ofType(bia, "TYPING").size(), "com 2 s completos volta a passar");

        // o limite é por sessão: outra pessoa não é afetada
        typing(h, caio, msg);
        assertEquals(3, ofType(bia, "TYPING").size());
    }

    @Test
    @DisplayName("Fechar a conexão libera o controle de taxa daquela sessão")
    void closingForgetsTheSession() throws Exception {
        Clock clock = new Clock();
        RetroWebSocketHandler h = retro(clock, new AtomicReference<>(allowAll()));
        String board = "rt-ws-4";
        WebSocketSession ana = retroSession("1", board, "&typing=1", "ana");
        WebSocketSession bia = retroSession("2", board, "&typing=1", "bia");
        h.afterConnectionEstablished(ana);
        h.afterConnectionEstablished(bia);
        clearInvocations(ana, bia);
        String msg = "{\"type\":\"TYPING\"}";

        typing(h, ana, msg);
        h.afterConnectionClosed(ana, org.springframework.web.socket.CloseStatus.NORMAL);
        h.afterConnectionEstablished(ana);
        clearInvocations(bia);
        typing(h, ana, msg);

        assertEquals(1, ofType(bia, "TYPING").size());
    }

    @Test
    @DisplayName("Card antes da revelação dos autores sai anônimo: sem userId")
    void anonymousCardTypingHidesTheSender() throws Exception {
        Clock clock = new Clock();
        RetroTypingGate gate = (b, u, r, scope, ch) -> Optional.of(new RetroTypingGate.Route(null, true));
        RetroWebSocketHandler h = retro(clock, new AtomicReference<>(gate));
        String board = "rt-ws-5";
        WebSocketSession ana = retroSession("1", board, "&typing=1", "ana");
        WebSocketSession bia = retroSession("2", board, "&typing=1", "bia");
        h.afterConnectionEstablished(ana);
        h.afterConnectionEstablished(bia);
        clearInvocations(ana, bia);

        typing(h, ana, "{\"type\":\"TYPING\",\"scope\":\"card\",\"columnKey\":\"bad\"}");

        JsonNode payload = ofType(bia, "TYPING").get(0).get("payload");
        assertTrue(payload.get("userId").isNull());
        assertTrue(payload.get("anonymous").asBoolean());
        assertFalse(ofType(bia, "TYPING").get(0).toString().contains("ana"));
    }

    @Test
    @DisplayName("Chat: o evento só vai ao público do canal (DM = duas pessoas) e carrega o channelId")
    void chatTypingHonoursAudience() throws Exception {
        Clock clock = new Clock();
        RetroTypingGate gate = (b, u, r, scope, ch) ->
                Optional.of(new RetroTypingGate.Route(Set.of("ana", "bia"), false));
        RetroWebSocketHandler h = retro(clock, new AtomicReference<>(gate));
        String board = "rt-ws-6";
        WebSocketSession ana = retroSession("1", board, "&typing=1", "ana");
        WebSocketSession bia = retroSession("2", board, "&typing=1", "bia");
        WebSocketSession caio = retroSession("3", board, "&typing=1", "caio");
        for (WebSocketSession s : List.of(ana, bia, caio)) h.afterConnectionEstablished(s);
        clearInvocations(ana, bia, caio);

        typing(h, ana, "{\"type\":\"TYPING\",\"scope\":\"chat\",\"channelId\":\"dm_ana_bia\"}");

        JsonNode payload = ofType(bia, "TYPING").get(0).get("payload");
        assertEquals("chat", payload.get("scope").asText());
        assertEquals("dm_ana_bia", payload.get("channelId").asText());
        assertEquals("ana", payload.get("userId").asText());
        verify(caio, never()).sendMessage(any());
    }

    @Test
    @DisplayName("Mensagens inválidas são ignoradas: JSON quebrado, outro tipo, escopo desconhecido, chat sem canal, corpo grande, gate nega")
    void invalidMessagesAreIgnored() throws Exception {
        Clock clock = new Clock();
        AtomicReference<RetroTypingGate> gate = new AtomicReference<>(allowAll());
        RetroWebSocketHandler h = retro(clock, gate);
        String board = "rt-ws-7";
        WebSocketSession ana = retroSession("1", board, "&typing=1", "ana");
        WebSocketSession bia = retroSession("2", board, "&typing=1", "bia");
        h.afterConnectionEstablished(ana);
        h.afterConnectionEstablished(bia);
        clearInvocations(ana, bia);

        for (String bad : List.of("não é json", "{\"type\":\"CHAT\",\"text\":\"oi\"}", "{\"type\":\"TYPING\",\"scope\":\"x\"}",
                "{\"type\":\"TYPING\",\"scope\":\"chat\"}", "[]", "{\"type\":\"TYPING\",\"pad\":\"" + "x".repeat(600) + "\"}")) {
            clock.now.addAndGet(10_000);
            typing(h, ana, bad);
        }
        gate.set((b, u, r, s, c) -> Optional.empty());
        clock.now.addAndGet(10_000);
        typing(h, ana, "{\"type\":\"TYPING\"}");
        gate.set(null);
        clock.now.addAndGet(10_000);
        typing(h, ana, "{\"type\":\"TYPING\"}");

        verify(bia, never()).sendMessage(any());
    }

    @Test
    @DisplayName("Retrocompatível: o handler sem gate (testes/construtor antigo) continua ignorando mensagens do cliente")
    void legacyConstructorIgnoresClientMessages() throws Exception {
        RetroWebSocketHandler h = new RetroWebSocketHandler(JSON);
        WebSocketSession ana = retroSession("1", "rt-ws-8", "&typing=1", "ana");
        WebSocketSession bia = retroSession("2", "rt-ws-8", "&typing=1", "bia");
        h.afterConnectionEstablished(ana);
        h.afterConnectionEstablished(bia);
        clearInvocations(ana, bia);

        typing(h, ana, "{\"type\":\"TYPING\"}");

        verify(bia, never()).sendMessage(any());
    }
}
