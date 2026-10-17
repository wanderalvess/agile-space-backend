package com.agilespace.backend.websocket;

import com.agilespace.backend.security.JwtTokenUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
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

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Radar de clima e Retrospectiva (V79) de ponta a ponta: Tomcat real, JWT, Postgres real e clientes WebSocket de
 * verdade. Cobre a migração (idempotente, sobre base com dados antigos), a contagem de respostas sem vazar voto, o
 * encerramento com permissões, a etapa da apresentação com opt-in e o "escrevendo…" da Retro. Sem Docker é ignorado.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class CeremonyRadarRetroEndToEndIntegrationTest {

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
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    private String token(String userId, String role) {
        return jwt.generateToken(userId, userId + "@totvs.com.br", "Pessoa " + userId, role, null, null, null);
    }

    private String member(String userId) {
        return token(userId, "MEMBER");
    }

    // ------------------------------------------------------------------ helpers HTTP

    private ResponseEntity<String> call(HttpMethod method, String path, String userId, String body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(member(userId));
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(body, h), String.class);
    }

    private JsonNode ok(ResponseEntity<String> response, HttpStatus expected) throws Exception {
        assertEquals(expected, response.getStatusCode(), response.getBody());
        return response.getBody() == null || response.getBody().isBlank() ? null : JSON.readTree(response.getBody());
    }

    // ------------------------------------------------------------------ helpers WebSocket

    private static final class Client extends TextWebSocketHandler {
        final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        final List<JsonNode> all = new java.util.concurrent.CopyOnWriteArrayList<>();
        WebSocketSession session;

        @Override
        public void afterConnectionEstablished(WebSocketSession s) {
            session = s;
        }

        @Override
        protected void handleTextMessage(WebSocketSession s, TextMessage message) throws Exception {
            JsonNode m = JSON.readTree(message.getPayload());
            all.add(m);
            messages.add(m);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
        }

        /** Espera uma mensagem do tipo; descarta as de outros tipos. null se não vier em 5 s. */
        JsonNode await(String type) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                JsonNode m = messages.poll(200, TimeUnit.MILLISECONDS);
                if (m != null && type.equals(m.get("type").asText())) return m;
            }
            return null;
        }

        /** Esquece o que já chegou (fila e histórico). */
        void reset() {
            messages.clear();
            all.clear();
        }

        /** Espera um período de silêncio e devolve TUDO o que chegou desde o último reset/quiet (inclusive o já consumido por await). */
        List<JsonNode> quiet() throws InterruptedException {
            Thread.sleep(700);
            List<JsonNode> out = new ArrayList<>(all);
            reset();
            return out;
        }

        void send(String json) throws Exception {
            session.sendMessage(new TextMessage(json));
        }

        void close() throws Exception {
            session.close();
        }
    }

    private Client connect(String path, String userId, String extraQuery) throws Exception {
        Client c = new Client();
        new StandardWebSocketClient()
                .execute(c, "ws://localhost:" + port + path + "?token=" + member(userId) + extraQuery)
                .get(10, TimeUnit.SECONDS);
        return c;
    }

    private static boolean has(List<JsonNode> messages, String type) {
        return messages.stream().anyMatch(m -> type.equals(m.get("type").asText()));
    }

    // ------------------------------------------------------------------ migração

    private void runV79() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(
                    new ClassPathResource("db/migration/V79__ceremony_radar_retro.sql"), StandardCharsets.UTF_8));
        }
    }

    private int columns(String table, String... names) {
        String in = String.join(",", java.util.Arrays.stream(names).map(n -> "'" + n + "'").toList());
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_name = ? AND column_name IN (" + in + ")",
                Integer.class, table);
    }

    @Test
    void v79IsIdempotentAndKeepsLegacyRowsReadable() throws Exception {
        // base "antiga": sem as colunas da V79, com um radar encerrado, um aberto e uma retro com participante
        jdbc.execute("ALTER TABLE health_check_boards DROP COLUMN IF EXISTS finished_at, DROP COLUMN IF EXISTS presentation_stage");
        jdbc.execute("ALTER TABLE retro_boards DROP COLUMN IF EXISTS finished_at, DROP COLUMN IF EXISTS lifecycle");
        jdbc.execute("ALTER TABLE retro_participants DROP COLUMN IF EXISTS checkin_note");
        jdbc.update("INSERT INTO health_check_boards (id, creator_id, status, created_at, scale_type, team, dimensions) "
                + "VALUES ('hc-old-fin', 'ana', 'finished', '2026-01-01T10:00:00Z', 'traffic_light', 'SQV', '[{\"key\":\"a\",\"title\":\"A\"}]'::jsonb)");
        jdbc.update("INSERT INTO health_check_boards (id, creator_id, status, created_at, scale_type, team, dimensions) "
                + "VALUES ('hc-old-open', 'ana', 'collecting', '2026-01-02T10:00:00Z', 'emojis', 'SQV', '[{\"key\":\"a\",\"title\":\"A\"}]'::jsonb)");
        jdbc.update("INSERT INTO retro_boards (id, creator_id, title, version) VALUES ('rt-old', 'ana', 'Retro antiga', 0)");
        jdbc.update("INSERT INTO retro_participants (db_id, user_id, board_id, nickname) VALUES ('rt-old_ana', 'ana', 'rt-old', 'Ana')");
        long boardsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM health_check_boards", Long.class);

        runV79();
        runV79(); // segunda vez: não pode falhar nem mexer nos dados

        assertEquals(2, columns("health_check_boards", "finished_at", "presentation_stage"));
        assertEquals(2, columns("retro_boards", "finished_at", "lifecycle"));
        assertEquals(1, columns("retro_participants", "checkin_note"));
        assertEquals(boardsBefore, jdbc.queryForObject("SELECT COUNT(*) FROM health_check_boards", Long.class));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM health_check_boards WHERE finished_at IS NOT NULL OR presentation_stage IS NOT NULL", Long.class),
                "nenhuma data é inventada para os radares antigos");
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM retro_boards WHERE finished_at IS NOT NULL OR lifecycle IS NOT NULL", Long.class));

        // o código novo lê as linhas antigas: status derivado do estado atual, data nula
        JsonNode finished = ok(call(HttpMethod.GET, "/api/health-checks/hc-old-fin", "ana", null), HttpStatus.OK);
        assertEquals("finished", finished.get("status").asText());
        assertEquals("FINISHED", finished.get("lifecycleStatus").asText());
        assertTrue(finished.get("finishedAt").isNull());
        assertTrue(finished.get("presentationStage").isNull());
        assertEquals(0, finished.get("respondedCount").asInt());
        JsonNode open = ok(call(HttpMethod.GET, "/api/health-checks/hc-old-open", "ana", null), HttpStatus.OK);
        assertEquals("OPEN", open.get("lifecycleStatus").asText());

        JsonNode retro = ok(call(HttpMethod.GET, "/api/retros/rt-old", "ana", null), HttpStatus.OK);
        assertEquals("OPEN", retro.get("lifecycleStatus").asText());
        assertTrue(retro.get("finishedAt").isNull());
        JsonNode participants = ok(call(HttpMethod.GET, "/api/retros/rt-old/participants", "ana", null), HttpStatus.OK);
        assertEquals("Ana", participants.get(0).get("nickname").asText());
        assertTrue(participants.get(0).get("checkinNote") == null || participants.get(0).get("checkinNote").isNull());

        // e o encerramento/reabertura funciona sobre a linha antiga
        assertEquals("FINISHED", ok(call(HttpMethod.POST, "/api/retros/rt-old/finish", "ana", null), HttpStatus.OK).get("lifecycleStatus").asText());
        assertEquals("OPEN", ok(call(HttpMethod.POST, "/api/retros/rt-old/reopen", "ana", null), HttpStatus.OK).get("lifecycleStatus").asText());
    }

    // ------------------------------------------------------------------ Radar

    @Test
    void radarProgressNeverLeaksVotesFinishAndPresentationAreOptIn() throws Exception {
        String id = "hc-e2e-1";
        ok(call(HttpMethod.POST, "/api/health-checks", "ana", "{\"id\":\"" + id + "\",\"scaleType\":\"traffic_light\","
                + "\"team\":\"SQE\",\"sprintName\":\"S1\",\"dimensions\":[{\"key\":\"a\",\"title\":\"A\"},{\"key\":\"b\",\"title\":\"B\"}]}"),
                HttpStatus.CREATED);
        for (String who : List.of("ana", "bia", "caio")) {
            ok(call(HttpMethod.POST, "/api/health-checks/" + id + "/participants", who, "{\"nickname\":\"" + who + "\",\"role\":\"DEV\"}"),
                    HttpStatus.CREATED);
        }
        JsonNode initial = ok(call(HttpMethod.GET, "/api/health-checks/" + id, "caio", null), HttpStatus.OK);
        assertEquals(3, initial.get("participantsCount").asInt());
        assertEquals(0, initial.get("respondedCount").asInt());
        assertEquals("OPEN", initial.get("lifecycleStatus").asText());

        Client ana = connect("/ws/health-check/" + id, "ana", "&presentation=1");
        Client bia = connect("/ws/health-check/" + id, "bia", "&presentation=1");
        Client caio = connect("/ws/health-check/" + id, "caio", ""); // frontend antigo: sem opt-in

        // bia dá a primeira resposta: a sala toda recebe o BOARD_UPDATED com a contagem (sem voto algum)
        ok(call(HttpMethod.POST, "/api/health-checks/" + id + "/votes", "bia", "{\"dimensionKey\":\"a\",\"value\":\"red\",\"comment\":\"segredo-da-bia\"}"),
                HttpStatus.CREATED);
        for (Client c : List.of(ana, bia, caio)) {
            JsonNode update = c.await("BOARD_UPDATED");
            assertNotNull(update, "primeira resposta tem de avisar a sala");
            assertEquals(1, update.get("payload").get("respondedCount").asInt());
            assertEquals(0, update.get("payload").get("completedCount").asInt());
            assertFalse(update.toString().contains("segredo-da-bia"));
            assertFalse(update.toString().contains("\"red\""));
        }
        // o VOTE_SAVED (com o comentário) continua só para a própria bia; ninguém mais vê voto de ninguém
        assertTrue(has(bia.quiet(), "VOTE_SAVED"));
        assertFalse(has(caio.quiet(), "VOTE_SAVED"));
        assertFalse(has(ana.quiet(), "VOTE_SAVED"));

        // trocar o voto já dado e votar na última dimensão: só a última (completou) gera novo BOARD_UPDATED
        ok(call(HttpMethod.POST, "/api/health-checks/" + id + "/votes", "bia", "{\"dimensionKey\":\"a\",\"value\":\"green\"}"), HttpStatus.CREATED);
        assertFalse(has(ana.quiet(), "BOARD_UPDATED"), "trocar um voto não muda a contagem");
        ok(call(HttpMethod.POST, "/api/health-checks/" + id + "/votes", "bia", "{\"dimensionKey\":\"b\",\"value\":\"yellow\"}"), HttpStatus.CREATED);
        JsonNode completed = ana.await("BOARD_UPDATED");
        assertNotNull(completed);
        assertEquals(1, completed.get("payload").get("respondedCount").asInt());
        assertEquals(1, completed.get("payload").get("completedCount").asInt());

        // GET: números iguais para todos; nenhum valor de voto no quadro; votos abertos seguem só os do próprio
        String boardJson = call(HttpMethod.GET, "/api/health-checks/" + id, "caio", null).getBody();
        JsonNode board = JSON.readTree(boardJson);
        assertEquals(3, board.get("participantsCount").asInt());
        assertEquals(1, board.get("respondedCount").asInt());
        assertEquals(1, board.get("completedCount").asInt());
        assertFalse(boardJson.contains("segredo-da-bia"));
        assertFalse(boardJson.contains("pendingParticipantIds"));
        assertEquals(0, ok(call(HttpMethod.GET, "/api/health-checks/" + id + "/votes", "caio", null), HttpStatus.OK).size(),
                "coleta aberta: caio não enxerga o voto da bia");

        // /progress: pendentes só para o facilitador
        JsonNode forFacilitator = ok(call(HttpMethod.GET, "/api/health-checks/" + id + "/progress", "ana", null), HttpStatus.OK);
        Set<String> pending = new HashSet<>();
        forFacilitator.get("pendingParticipantIds").forEach(n -> pending.add(n.asText()));
        assertEquals(Set.of("ana", "caio"), pending);
        assertEquals(2, forFacilitator.get("dimensionsCount").asInt());
        String forVoter = call(HttpMethod.GET, "/api/health-checks/" + id + "/progress", "caio", null).getBody();
        assertFalse(forVoter.contains("pendingParticipantIds"));
        assertFalse(forVoter.contains("segredo-da-bia"));
        assertEquals(HttpStatus.NOT_FOUND, call(HttpMethod.GET, "/api/health-checks/nao-existe/progress", "ana", null).getStatusCode());

        // apresentação só depois de encerrar e só pelo facilitador
        assertEquals(HttpStatus.CONFLICT, call(HttpMethod.PUT, "/api/health-checks/" + id + "/presentation", "ana", "{\"stage\":\"radar\"}").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, call(HttpMethod.POST, "/api/health-checks/" + id + "/finish", "bia", null).getStatusCode());
        JsonNode finished = ok(call(HttpMethod.POST, "/api/health-checks/" + id + "/finish", "ana", null), HttpStatus.OK);
        assertEquals("FINISHED", finished.get("lifecycleStatus").asText());
        String stamp = finished.get("finishedAt").asText();
        assertDoesNotThrow(() -> java.time.Instant.parse(stamp));
        assertEquals(stamp, ok(call(HttpMethod.POST, "/api/health-checks/" + id + "/finish", "ana", null), HttpStatus.OK).get("finishedAt").asText());
        for (Client c : List.of(ana, bia, caio)) c.reset();

        assertEquals(HttpStatus.FORBIDDEN, call(HttpMethod.PUT, "/api/health-checks/" + id + "/presentation", "bia", "{\"stage\":\"radar\"}").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, call(HttpMethod.DELETE, "/api/health-checks/" + id + "/presentation", "caio", null).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, call(HttpMethod.PUT, "/api/health-checks/" + id + "/presentation", "ana", "{\"stage\":\"com espaço\"}").getStatusCode());
        assertFalse(has(bia.quiet(), "PRESENTATION_STAGE"), "tentativa negada não publica nada");

        JsonNode staged = ok(call(HttpMethod.PUT, "/api/health-checks/" + id + "/presentation", "ana", "{\"stage\":\"radar\"}"), HttpStatus.OK);
        assertEquals("radar", staged.get("presentationStage").asText());
        JsonNode event = bia.await("PRESENTATION_STAGE");
        assertNotNull(event, "quem fez opt-in segue o facilitador");
        assertEquals("radar", event.get("payload").get("stage").asText());
        assertNotNull(ana.await("PRESENTATION_STAGE"));
        List<JsonNode> oldClient = caio.quiet();
        assertFalse(has(oldClient, "PRESENTATION_STAGE"), "sem ?presentation=1 nada novo chega");
        assertFalse(has(oldClient, "BOARD_UPDATED"), "mudar de etapa não acorda o cliente antigo");
        assertEquals("radar", ok(call(HttpMethod.GET, "/api/health-checks/" + id, "caio", null), HttpStatus.OK).get("presentationStage").asText(),
                "quem entra depois lê a etapa no GET");

        ok(call(HttpMethod.DELETE, "/api/health-checks/" + id + "/presentation", "ana", null), HttpStatus.OK);
        JsonNode cleared = bia.await("PRESENTATION_STAGE");
        assertNotNull(cleared);
        assertTrue(cleared.get("payload").get("stage").isNull());
        assertTrue(ok(call(HttpMethod.GET, "/api/health-checks/" + id, "bia", null), HttpStatus.OK).get("presentationStage").isNull());

        for (Client c : List.of(ana, bia, caio)) c.close();
    }

    @Test
    void radarOldClientBodyStillWorks() throws Exception {
        // corpo antigo: sem nenhum campo novo, e eco de um GET (com campos novos) no POST de um radar existente
        String id = "hc-e2e-2";
        JsonNode created = ok(call(HttpMethod.POST, "/api/health-checks", "ana", "{\"id\":\"" + id + "\",\"scaleType\":\"emojis\","
                + "\"dimensions\":[{\"key\":\"a\",\"title\":\"A\"}],\"lifecycleStatus\":\"FINISHED\",\"finishedAt\":\"2020-01-01T00:00:00Z\","
                + "\"presentationStage\":\"plan\",\"respondedCount\":7}"), HttpStatus.CREATED);
        assertEquals("collecting", created.get("status").asText());
        assertEquals("OPEN", created.get("lifecycleStatus").asText());
        assertTrue(created.get("finishedAt").isNull());
        assertTrue(created.get("presentationStage").isNull());
        assertEquals(0, created.get("respondedCount").asInt());

        String echoed = call(HttpMethod.GET, "/api/health-checks/" + id, "ana", null).getBody();
        ok(call(HttpMethod.POST, "/api/health-checks", "ana", echoed), HttpStatus.OK);
    }

    // ------------------------------------------------------------------ Retro

    @Test
    void retroFinishReopenReadOnlyAndCheckinNote() throws Exception {
        String id = "rt-e2e-1";
        String cols = "[{\"id\":\"good\",\"title\":\"Bem\",\"theme\":\"success\",\"columnOrder\":0}]";
        JsonNode created = ok(call(HttpMethod.POST, "/api/retros", "ana", "{\"id\":\"" + id + "\",\"title\":\"Retro\",\"columns\":" + cols
                + ",\"lifecycleStatus\":\"FINISHED\",\"finishedAt\":\"2020-01-01T00:00:00\"}"), HttpStatus.CREATED);
        assertEquals("OPEN", created.get("lifecycleStatus").asText(), "o corpo nunca cria encerrada");
        assertTrue(created.get("finishedAt").isNull());

        JsonNode joined = ok(call(HttpMethod.POST, "/api/retros/" + id + "/participants", "bia",
                "{\"nickname\":\"Bia\",\"healthCheckAnswer\":\"good\",\"checkinNote\":\"  semana corrida  \"}"), HttpStatus.CREATED);
        assertEquals("semana corrida", joined.get("checkinNote").asText());
        assertEquals(HttpStatus.BAD_REQUEST, call(HttpMethod.POST, "/api/retros/" + id + "/participants", "bia",
                "{\"nickname\":\"Bia\",\"checkinNote\":\"" + "x".repeat(501) + "\"}").getStatusCode());
        JsonNode kept = ok(call(HttpMethod.POST, "/api/retros/" + id + "/participants", "bia", "{\"nickname\":\"Bia\"}"), HttpStatus.OK);
        assertEquals("semana corrida", kept.get("checkinNote").asText(), "campo ausente não apaga");
        JsonNode cleared = ok(call(HttpMethod.POST, "/api/retros/" + id + "/participants", "bia", "{\"nickname\":\"Bia\",\"checkinNote\":\"\"}"), HttpStatus.OK);
        assertTrue(cleared.get("checkinNote") == null || cleared.get("checkinNote").isNull());

        Client bia = connect("/ws/retro/" + id, "bia", "");

        // permissões de encerrar/reabrir
        assertEquals(HttpStatus.FORBIDDEN, call(HttpMethod.POST, "/api/retros/" + id + "/finish", "bia", null).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, call(HttpMethod.POST, "/api/retros/" + id + "/reopen", "bia", null).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, call(HttpMethod.POST, "/api/retros/nao-existe/finish", "ana", null).getStatusCode());

        JsonNode finished = ok(call(HttpMethod.POST, "/api/retros/" + id + "/finish", "ana", null), HttpStatus.OK);
        assertEquals("FINISHED", finished.get("lifecycleStatus").asText());
        assertFalse(finished.get("finishedAt").isNull());
        JsonNode event = bia.await("BOARD_UPDATED");
        assertNotNull(event);
        assertEquals("FINISHED", event.get("payload").get("lifecycleStatus").asText());

        // somente leitura para o participante; o facilitador segue editando; ler continua livre
        String card = "{\"id\":\"c1\",\"columnKey\":\"good\",\"content\":\"texto\",\"order\":1}";
        ResponseEntity<String> blocked = call(HttpMethod.POST, "/api/retros/" + id + "/cards", "bia", card);
        assertEquals(HttpStatus.CONFLICT, blocked.getStatusCode());
        assertTrue(blocked.getBody().contains("encerrada"), blocked.getBody());
        ok(call(HttpMethod.GET, "/api/retros/" + id + "/cards", "bia", null), HttpStatus.OK);
        ok(call(HttpMethod.POST, "/api/retros/" + id + "/cards", "ana", card), HttpStatus.CREATED);

        // reabrir devolve a escrita
        JsonNode reopened = ok(call(HttpMethod.POST, "/api/retros/" + id + "/reopen", "ana", null), HttpStatus.OK);
        assertEquals("OPEN", reopened.get("lifecycleStatus").asText());
        assertTrue(reopened.get("finishedAt").isNull());
        ok(call(HttpMethod.POST, "/api/retros/" + id + "/cards", "bia",
                "{\"id\":\"c2\",\"columnKey\":\"good\",\"content\":\"da bia\",\"order\":2}"), HttpStatus.CREATED);
        bia.close();
    }

    @Test
    void retroTypingIsOptInRateLimitedAndUsesTheTokenIdentity() throws Exception {
        String id = "rt-e2e-2";
        ok(call(HttpMethod.POST, "/api/retros", "ana", "{\"id\":\"" + id + "\",\"title\":\"Retro\",\"columns\":"
                + "[{\"id\":\"good\",\"title\":\"Bem\",\"theme\":\"success\",\"columnOrder\":0}]}"), HttpStatus.CREATED);
        for (String who : List.of("bia", "caio")) {
            ok(call(HttpMethod.POST, "/api/retros/" + id + "/participants", who, "{\"nickname\":\"" + who + "\"}"), HttpStatus.CREATED);
        }
        Client ana = connect("/ws/retro/" + id, "ana", "&typing=1");
        Client bia = connect("/ws/retro/" + id, "bia", "&typing=1");
        Client caio = connect("/ws/retro/" + id, "caio", ""); // frontend antigo

        // 1) autores ainda ocultos: o evento sai anônimo. 2) logo em seguida: limitado pela taxa e descartado
        ana.send("{\"type\":\"TYPING\",\"scope\":\"card\",\"columnKey\":\"good\",\"userId\":\"vitima\"}");
        ana.send("{\"type\":\"TYPING\",\"scope\":\"card\",\"columnKey\":\"good\"}");
        JsonNode anonymous = bia.await("TYPING");
        assertNotNull(anonymous);
        assertTrue(anonymous.get("payload").get("anonymous").asBoolean());
        assertTrue(anonymous.get("payload").get("userId").isNull());
        assertEquals("good", anonymous.get("payload").get("columnKey").asText());
        assertEquals(5000, anonymous.get("payload").get("expiresInMs").asInt());
        assertFalse(anonymous.toString().contains("vitima"));
        assertEquals(1, bia.quiet().stream().filter(m -> "TYPING".equals(m.get("type").asText())).count(),
                "o segundo evento dentro de 2 s é descartado");
        assertFalse(has(caio.quiet(), "TYPING"), "cliente sem ?typing=1 nunca recebe");
        assertFalse(has(ana.quiet(), "TYPING"), "o remetente não recebe o próprio evento");

        // autores revelados: o evento identifica quem escreve, e o id é o do token (ana), não o do corpo
        ok(call(HttpMethod.PATCH, "/api/retros/" + id, "ana", "{\"isAuthorsRevealed\":true}"), HttpStatus.OK);
        Thread.sleep(2200);
        for (Client c : List.of(ana, bia, caio)) c.reset();
        ana.send("{\"type\":\"TYPING\",\"scope\":\"card\",\"userId\":\"vitima\"}");
        JsonNode identified = bia.await("TYPING");
        assertNotNull(identified);
        assertFalse(identified.get("payload").get("anonymous").asBoolean());
        assertEquals("ana", identified.get("payload").get("userId").asText());

        // chat geral
        Thread.sleep(2200);
        bia.reset();
        ana.send("{\"type\":\"TYPING\",\"scope\":\"chat\",\"channelId\":\"geral\"}");
        JsonNode chat = bia.await("TYPING");
        assertNotNull(chat);
        assertEquals("chat", chat.get("payload").get("scope").asText());
        assertEquals("geral", chat.get("payload").get("channelId").asText());

        // DM entre ana e bia: o caio (mesmo com opt-in) não recebe
        Client caioOptIn = connect("/ws/retro/" + id, "caio", "&typing=1");
        Thread.sleep(2200);
        bia.reset();
        caioOptIn.reset();
        ana.send("{\"type\":\"TYPING\",\"scope\":\"chat\",\"channelId\":\"dm_ana_bia\"}");
        assertNotNull(bia.await("TYPING"));
        assertFalse(has(caioOptIn.quiet(), "TYPING"), "DM só vai às duas pessoas");

        for (Client c : List.of(ana, bia, caio, caioOptIn)) c.close();
    }
}
