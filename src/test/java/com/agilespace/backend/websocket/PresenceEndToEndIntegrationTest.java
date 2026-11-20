package com.agilespace.backend.websocket;

import com.agilespace.backend.security.JwtTokenUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Presença de ponta a ponta: Tomcat real, handshake com JWT, Postgres real (para a sala de Poker existir) e clientes
 * WebSocket de verdade. Sem Docker o teste é ignorado, como os demais de integração.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class PresenceEndToEndIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JwtTokenUtil jwt;
    @Autowired private PresenceRegistry registry;

    private String token(String userId) {
        return jwt.generateToken(userId, userId + "@totvs.com.br", "Pessoa " + userId, "ADMIN", null, null, null);
    }

    /** Cliente WebSocket que guarda tudo o que o servidor manda. */
    private static final class Client extends TextWebSocketHandler {
        final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        WebSocketSession session;

        @Override
        public void afterConnectionEstablished(WebSocketSession s) {
            session = s;
        }

        @Override
        protected void handleTextMessage(WebSocketSession s, TextMessage message) throws Exception {
            messages.add(JSON.readTree(message.getPayload()));
        }

        @Override
        public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
        }

        JsonNode next() throws InterruptedException {
            return messages.poll(5, TimeUnit.SECONDS);
        }

        List<JsonNode> drainAfterQuietPeriod() throws InterruptedException {
            List<JsonNode> out = new ArrayList<>();
            JsonNode m;
            while ((m = messages.poll(700, TimeUnit.MILLISECONDS)) != null) out.add(m);
            return out;
        }
    }

    private Client connect(String path, String userId, String extraQuery) throws Exception {
        Client c = new Client();
        new StandardWebSocketClient()
                .execute(c, "ws://localhost:" + port + path + "?token=" + token(userId) + extraQuery)
                .get(10, TimeUnit.SECONDS);
        return c;
    }

    private static List<String> online(JsonNode event) {
        List<String> ids = new ArrayList<>();
        event.get("payload").get("online").forEach(n -> ids.add(n.asText()));
        return ids;
    }

    private ResponseEntity<String> get(String path, String bearerUser) {
        HttpHeaders h = new HttpHeaders();
        if (bearerUser != null) h.setBearerAuth(token(bearerUser));
        return rest.exchange("/api/presence" + path, HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    @Test
    void pokerRoomPresenceThroughRealWebSocketsAndRest() throws Exception {
        String room = "presence-e2e-poker";
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token("ana"));
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> created = rest.exchange("/api/poker", HttpMethod.POST,
                new HttpEntity<>("{\"id\":\"" + room + "\",\"title\":\"Sala\",\"deckType\":\"fibonacci\"}", h), String.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());

        // sala existe e ainda ninguém conectado
        ResponseEntity<String> empty = get("/poker/" + room, "ana");
        assertEquals(HttpStatus.OK, empty.getStatusCode());
        assertEquals(0, JSON.readTree(empty.getBody()).get("total").asInt());

        Client ana = connect("/ws/poker/" + room, "ana", "&presence=1");
        JsonNode snapshot = ana.next();
        assertNotNull(snapshot, "o snapshot inicial tem de chegar logo depois do handshake");
        assertEquals("PRESENCE_UPDATED", snapshot.get("type").asText());
        assertEquals(room, snapshot.get("roomId").asText());
        assertEquals(List.of("ana"), online(snapshot));

        // bia usa o frontend antigo (sem presence=1): entra na lista dos outros, mas não recebe o evento novo
        Client bia = connect("/ws/poker/" + room, "bia", "");
        JsonNode update = ana.next();
        assertEquals(List.of("ana", "bia"), online(update));
        assertTrue(update.get("payload").get("version").asLong() > snapshot.get("payload").get("version").asLong());

        // segunda aba da ana: a lista não muda, ninguém mais é avisado
        Client ana2 = connect("/ws/poker/" + room, "ana", "&presence=1");
        assertEquals(List.of("ana", "bia"), online(ana2.next()));
        assertTrue(ana.drainAfterQuietPeriod().isEmpty());

        ResponseEntity<String> rest2 = get("/poker/" + room, "qualquer-um");
        assertEquals(HttpStatus.OK, rest2.getStatusCode());
        JsonNode body = JSON.readTree(rest2.getBody());
        assertEquals("poker", body.get("module").asText());
        assertEquals(2, body.get("total").asInt());

        // a bia sai: quem pediu presença é avisado; a bia nunca recebeu nada de presença
        bia.session.close();
        JsonNode afterLeave = ana.next();
        assertEquals(List.of("ana"), online(afterLeave));
        assertTrue(bia.drainAfterQuietPeriod().isEmpty(), "sem presence=1 o cliente nunca recebe PRESENCE_UPDATED");

        ana.session.close();
        ana2.session.close();
        long deadline = System.currentTimeMillis() + 5000;
        while (registry.roomCount() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertEquals(0, registry.roomCount(), "sala vazia sai da memória");
        assertEquals(0, registry.sessionCount(PresenceModule.POKER));
    }

    @Test
    void showcaseSessionsStillWorkWithTheDecoratorAndCarryPresence() throws Exception {
        Client ana = connect("/ws/showcase/presence-e2e-showcase", "ana", "&presence=1");
        assertEquals(List.of("ana"), online(ana.next()));
        Client bia = connect("/ws/showcase/presence-e2e-showcase", "bia", "&presence=1");
        JsonNode update = ana.next();
        assertEquals("presence-e2e-showcase", update.get("sessionId").asText());
        assertEquals(List.of("ana", "bia"), online(update));
        ana.session.close();
        bia.session.close();
    }

    @Test
    void restRequiresTokenAndKnownRoom() throws Exception {
        assertEquals(HttpStatus.UNAUTHORIZED, get("/poker/qualquer", null).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, get("/poker/sala-que-nao-existe", "ana").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, get("/modulo-inexistente/x", "ana").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, get("/retro/quadro-que-nao-existe", "ana").getStatusCode());
    }
}
