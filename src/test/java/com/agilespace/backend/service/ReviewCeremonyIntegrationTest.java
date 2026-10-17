package com.agilespace.backend.service;

import com.agilespace.backend.security.JwtTokenUtil;
import com.agilespace.backend.websocket.ShowcaseWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Review (docs/ceremony-review-api.md) contra um Postgres real, pela API HTTP completa: migração V81 (idempotente e sobre
 * Reviews antigas), trava PO/SME por vínculo, participantes na finalização, apresentador, PATCH por card com concorrência,
 * mescla do POST antigo, eventos opt-in e JQL por squad. Sem Docker o teste é ignorado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ReviewCeremonyIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenUtil jwtTokenUtil;
    @Autowired private MembershipCache membershipCache;
    @Autowired private ShowcaseWebSocketHandler webSocketHandler;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE showcase_jql_queries, showcase_participants, showcase_task_files, showcase_task_metrics, showcase_tasks, "
                + "showcase_members, showcase_sessions, project_member_roles, squad_members, projects, users CASCADE");
        membershipCache.evictAll();
        project("SQ1");
        project("SQ2");
        user("adm", "ADMIN", null);
        user("criador", "MEMBER", "SQ1");
        member("criador", "SQ1", "DEVELOPER", "Developer", false);
        user("po", "MEMBER", "SQ1");
        member("po", "SQ1", "PRODUCT_OWNER", "Product Owner", true);
        user("sme", "MEMBER", "SQ1");
        member("sme", "SQ1", "SME", "SME", false);
        user("am", "MEMBER", "SQ1");
        member("am", "SQ1", "AGILE_MASTER", "Agile Master", true);
        user("dev", "MEMBER", "SQ1");
        member("dev", "SQ1", "DEVELOPER", "Developer", false);
        user("fora", "MEMBER", "SQ2");
        member("fora", "SQ2", "PRODUCT_OWNER", "Product Owner", true);
    }

    // ---------- fixtures ----------

    private void project(String id) {
        jdbc.update("INSERT INTO projects (id, name, tribe_name, segment_name) VALUES (?, ?, 'Tribo', 'Seg')", id, "Squad " + id);
    }

    private void user(String id, String role, String squadId) {
        jdbc.update("INSERT INTO users (id, email, name, role, is_active, is_guest, default_project_id) VALUES (?, ?, ?, ?, TRUE, FALSE, ?)",
                id, id + "@totvs.com.br", "Pessoa " + id, role, squadId);
    }

    private void member(String userId, String project, String key, String roleName, boolean leadership) {
        jdbc.update("INSERT INTO project_member_roles (id, project_id, role_name, role_key, display_name, email, user_id, is_leadership, source) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL')", UUID.randomUUID().toString(), project, roleName, key, "Nome " + userId,
                userId + "@totvs.com.br", userId, leadership);
    }

    private String token(String id) {
        String role = "adm".equals(id) ? "ADMIN" : "MEMBER";
        return "Bearer " + jwtTokenUtil.generateToken(id, id + "@totvs.com.br", "Pessoa " + id, role, null, null, null);
    }

    private MvcResult call(MockHttpServletRequestBuilder b, String user, String json) throws Exception {
        b.header("Authorization", token(user));
        if (json != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(b).andReturn();
    }

    private static JsonNode body(MvcResult r) throws Exception {
        String s = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return s.isEmpty() ? null : JSON.readTree(s);
    }

    private static String cardJson(String id, String title, String decision, String feedback, Long version) {
        return "{\"id\":\"" + id + "\",\"key\":\"PTV-" + id + "\",\"title\":\"" + title + "\",\"decision\":\"" + decision
                + "\",\"feedback\":\"" + feedback + "\",\"preparationStatus\":\"todo\"" + (version == null ? "" : ",\"version\":" + version)
                + ",\"evidence\":{\"problem\":\"P\",\"solution\":\"S\",\"dev\":\"\",\"qa\":\"\"},\"metrics\":[]}";
    }

    private static String sessionJson(String id, String status, String... cards) {
        return "{" + (id == null ? "" : "\"id\":\"" + id + "\",") + "\"name\":\"Review da Sprint\",\"status\":\"" + status
                + "\",\"squadName\":\"SQ1\",\"tasks\":[" + String.join(",", cards) + "],\"members\":[]}";
    }

    /** Cria a Review como o criador, com os cards dados, e devolve o id. */
    private String createSession(String status, String... cards) throws Exception {
        MvcResult r = call(post("/api/showcase-sessions"), "criador", sessionJson(null, status, cards));
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        return body(r).get("id").asText();
    }

    private JsonNode fetch(String id) throws Exception {
        MvcResult r = call(get("/api/showcase-sessions/" + id), "dev", null);
        assertEquals(200, r.getResponse().getStatus());
        return body(r);
    }

    private static JsonNode card(JsonNode session, String cardId) {
        for (JsonNode t : session.get("tasks")) {
            if (cardId.equals(t.get("id").asText())) return t;
        }
        throw new AssertionError("card " + cardId + " não está na sessão");
    }

    private void runV81() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(new ClassPathResource("db/migration/V81__review_ceremony_features.sql"), StandardCharsets.UTF_8));
        }
    }

    private WebSocketSession socket(String id, String userId, String sessionId, String query) throws Exception {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        when(s.getUri()).thenReturn(new URI("wss://x/ws/showcase/" + sessionId + "?token=t" + query));
        when(s.getAttributes()).thenReturn(Map.of("userId", userId, "role", "MEMBER"));
        webSocketHandler.afterConnectionEstablished(s);
        return s;
    }

    private List<JsonNode> received(WebSocketSession s) throws Exception {
        ArgumentCaptor<TextMessage> cap = ArgumentCaptor.forClass(TextMessage.class);
        verify(s, atLeast(0)).sendMessage(cap.capture());
        List<JsonNode> out = new ArrayList<>();
        for (TextMessage m : cap.getAllValues()) out.add(JSON.readTree(m.getPayload()));
        return out;
    }

    private static List<JsonNode> ofType(List<JsonNode> all, String type) {
        return all.stream().filter(n -> type.equals(n.get("type").asText())).toList();
    }

    // ---------- migração V81 ----------

    @Test
    void v81RunsTwiceOnABaseWithOldReviewsAndLeavesThemUsable() throws Exception {
        // Estado pré-V81: descarta só o que a V81 criou e põe uma Review "antiga" com cards.
        jdbc.execute("DROP TABLE IF EXISTS showcase_jql_queries");
        jdbc.execute("DROP TABLE IF EXISTS showcase_participants");
        jdbc.execute("ALTER TABLE showcase_tasks DROP COLUMN card_version, DROP COLUMN card_updated_at");
        jdbc.update("INSERT INTO showcase_sessions (id, name, status, created_at, created_by, squad_name) VALUES ('velha', 'Review antiga', 'finished', now(), 'criador', 'SQ1')");
        jdbc.update("INSERT INTO showcase_tasks (id, session_id, task_key, title, decision, task_order) VALUES ('velha-c1', 'velha', 'PTV-1', 'Card antigo', 'approved', 0)");

        runV81();
        runV81(); // 2x: idempotente

        assertEquals(1L, jdbc.queryForObject("SELECT card_version FROM showcase_tasks WHERE id = 'velha-c1'", Long.class));
        assertNull(jdbc.queryForObject("SELECT card_updated_at FROM showcase_tasks WHERE id = 'velha-c1'", java.sql.Timestamp.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM showcase_participants", Integer.class));
        assertEquals("Card antigo", jdbc.queryForObject("SELECT title FROM showcase_tasks WHERE id = 'velha-c1'", String.class), "dado antigo intacto");

        // A Review antiga abre sem lista de participantes, com versão 1, e aceita PATCH com version 1.
        JsonNode s = fetch("velha");
        assertEquals(0, s.get("participants").size());
        assertFalse(s.has("presenter"));
        assertEquals(1, card(s, "velha-c1").get("version").asInt());
        MvcResult patched = call(patch("/api/showcase-sessions/velha/cards/velha-c1"), "dev", "{\"version\":1,\"title\":\"Card antigo editado\"}");
        assertEquals(200, patched.getResponse().getStatus(), patched.getResponse().getContentAsString());
        assertEquals(2, body(patched).get("version").asInt());
    }

    @Test
    void v81KeepsNewTablesUsableAfterTheSecondRun() throws Exception {
        runV81();
        runV81();

        jdbc.update("INSERT INTO showcase_jql_queries (id, squad_id, name, jql, visibility, created_by, created_at) VALUES ('q1', 'SQ1', 'A', 'x', 'SQUAD', 'dev', now())");
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () ->
                jdbc.update("INSERT INTO showcase_jql_queries (id, squad_id, name, jql, visibility, created_by, created_at) VALUES ('q2', 'SQ1', 'a', 'x', 'SQUAD', 'dev', now())"),
                "mesmo nome (sem caixa), mesma pessoa e squad");
    }

    // ---------- retrocompatibilidade ----------

    @Test
    void oldClientFlowStillWorksAndSeesOnlyAdditiveFields() throws Exception {
        String id = createSession("planning", cardJson("c1", "Card 1", "open", "", null), cardJson("c2", "Card 2", "open", "", null));

        JsonNode s = fetch(id);
        assertEquals(2, s.get("tasks").size());
        assertEquals(1, card(s, "c1").get("version").asInt());
        assertEquals(0, s.get("participants").size());
        assertFalse(s.has("presenter"));
        assertFalse(s.has("staleCardIds"));

        // O cliente antigo regrava a sessão inteira (devolvendo o version que leu): conteúdo igual não sobe versão.
        MvcResult again = call(post("/api/showcase-sessions"), "dev", sessionJson(id, "planning",
                cardJson("c1", "Card 1", "open", "", 1L), cardJson("c2", "Card 2 editado", "open", "", 1L)));
        assertEquals(200, again.getResponse().getStatus());
        JsonNode after = fetch(id);
        assertEquals(1, card(after, "c1").get("version").asInt(), "card inalterado mantém a versão");
        assertEquals(2, card(after, "c2").get("version").asInt());
        assertEquals("Card 2 editado", card(after, "c2").get("title").asText());
        assertFalse(body(again).has("staleCardIds"));
    }

    @Test
    void clientsThatKnowNothingAboutVersionKeepLastWriteWins() throws Exception {
        String id = createSession("planning", cardJson("c1", "Original", "open", "", null));
        call(patch("/api/showcase-sessions/" + id + "/cards/c1"), "dev", "{\"version\":1,\"title\":\"Do PATCH\"}");

        MvcResult r = call(post("/api/showcase-sessions"), "dev", sessionJson(id, "planning", cardJson("c1", "Sem version", "open", "", null)));

        assertEquals(200, r.getResponse().getStatus());
        assertEquals("Sem version", card(fetch(id), "c1").get("title").asText());
        assertEquals(3, card(fetch(id), "c1").get("version").asInt());
    }

    // ---------- trava PO/SME ----------

    @Test
    void decisionLockByRoleForWholeSessionPost() throws Exception {
        String id = createSession("active", cardJson("c1", "Card 1", "open", "", null), cardJson("c2", "Card 2", "open", "", null));

        // Não-PO mandando a sessão inteira SEM mudar decisão/feedback: aceito (mesmo mudando conteúdo de preparação).
        MvcResult ok = call(post("/api/showcase-sessions"), "dev", sessionJson(id, "active",
                cardJson("c1", "Card 1 editado", "open", "", 1L), cardJson("c2", "Card 2", "open", "", 1L)));
        assertEquals(200, ok.getResponse().getStatus(), ok.getResponse().getContentAsString());

        // Não-PO mudando o veredito: 403 com o código e os cards; NADA é gravado (nem o título que veio junto).
        MvcResult denied = call(post("/api/showcase-sessions"), "dev", sessionJson(id, "active",
                cardJson("c1", "Tentativa de título", "approved", "", 2L), cardJson("c2", "Card 2", "open", "", 1L)));
        assertEquals(403, denied.getResponse().getStatus());
        assertEquals("DECISION_RESTRICTED", body(denied).get("code").asText());
        assertEquals("c1", body(denied).get("cardIds").get(0).asText());
        assertFalse(body(denied).get("message").asText().isBlank());
        JsonNode unchanged = fetch(id);
        assertEquals("open", card(unchanged, "c1").get("decision").asText());
        assertEquals("Card 1 editado", card(unchanged, "c1").get("title").asText());

        // Feedback também é decisão.
        MvcResult feedback = call(post("/api/showcase-sessions"), "criador", sessionJson(id, "active",
                cardJson("c1", "Card 1 editado", "open", "Reprovei sem poder", 2L), cardJson("c2", "Card 2", "open", "", 1L)));
        assertEquals(403, feedback.getResponse().getStatus());

        // Quem pode: PO, SME, liderança (Agile Master) e ADMIN; quem é de outra squad, não (mesmo sendo PO lá).
        assertEquals(403, call(post("/api/showcase-sessions"), "fora", sessionJson(id, "active",
                cardJson("c1", "Card 1 editado", "approved", "", 2L), cardJson("c2", "Card 2", "open", "", 1L))).getResponse().getStatus());
        MvcResult po = call(post("/api/showcase-sessions"), "po", sessionJson(id, "active",
                cardJson("c1", "Card 1 editado", "approved", "Ok", 2L), cardJson("c2", "Card 2", "open", "", 1L)));
        assertEquals(200, po.getResponse().getStatus(), po.getResponse().getContentAsString());
        JsonNode c1 = card(fetch(id), "c1");
        assertEquals("approved", c1.get("decision").asText());
        assertEquals("po", c1.get("decidedBy").asText());
        assertEquals("Pessoa po", c1.get("decidedByName").asText());

        assertEquals(200, call(post("/api/showcase-sessions"), "sme", sessionJson(id, "active",
                cardJson("c1", "Card 1 editado", "approved", "Ok", 3L), cardJson("c2", "Card 2", "needs_adjustment", "Ajustar", 1L))).getResponse().getStatus());
        assertEquals(200, call(post("/api/showcase-sessions"), "am", sessionJson(id, "active",
                cardJson("c1", "Card 1 editado", "approved", "Ok", 3L), cardJson("c2", "Card 2", "rejected", "Não", 2L))).getResponse().getStatus());
        assertEquals(200, call(post("/api/showcase-sessions"), "adm", sessionJson(id, "active",
                cardJson("c1", "Card 1 editado", "open", "", 3L), cardJson("c2", "Card 2", "rejected", "Não", 3L))).getResponse().getStatus());
        assertEquals("open", card(fetch(id), "c1").get("decision").asText());
    }

    @Test
    void newCardCannotBeBornDecidedByANonDecider() throws Exception {
        String id = createSession("active", cardJson("c1", "Card 1", "open", "", null));

        MvcResult r = call(post("/api/showcase-sessions"), "dev", sessionJson(id, "active",
                cardJson("c1", "Card 1", "open", "", 1L), cardJson("c9", "Novo já aprovado", "approved", "", null)));

        assertEquals(403, r.getResponse().getStatus());
        assertEquals(1, fetch(id).get("tasks").size());
    }

    @Test
    void decisionLockForPatch() throws Exception {
        String id = createSession("active", cardJson("c1", "Card 1", "open", "", null));
        String url = "/api/showcase-sessions/" + id + "/cards/c1";

        MvcResult prep = call(patch(url), "dev", "{\"version\":1,\"preparationStatus\":\"done\",\"evidence\":{\"video\":\"https://v.example/x\"}}");
        assertEquals(200, prep.getResponse().getStatus(), prep.getResponse().getContentAsString());
        assertEquals(2, body(prep).get("version").asInt());

        MvcResult denied = call(patch(url), "dev", "{\"version\":2,\"decision\":\"approved\"}");
        assertEquals(403, denied.getResponse().getStatus());
        assertEquals("DECISION_RESTRICTED", body(denied).get("code").asText());
        MvcResult deniedFeedback = call(patch(url), "dev", "{\"version\":2,\"title\":\"X\",\"feedback\":\"Reprovado\"}");
        assertEquals(403, deniedFeedback.getResponse().getStatus());
        assertEquals("Card 1", card(fetch(id), "c1").get("title").asText(), "rejeita tudo, inclusive o título que veio junto");

        MvcResult sme = call(patch(url), "sme", "{\"version\":2,\"decision\":\"needs_adjustment\",\"feedback\":\"Ajustar o texto\"}");
        assertEquals(200, sme.getResponse().getStatus(), sme.getResponse().getContentAsString());
        assertEquals("sme", body(sme).get("decidedBy").asText());
        assertEquals(3, body(sme).get("version").asInt());
    }

    @Test
    void reviewWithoutSquadOnlyCreatorAndAdminDecide() throws Exception {
        MvcResult created = call(post("/api/showcase-sessions"), "criador",
                "{\"name\":\"Sem squad\",\"status\":\"active\",\"tasks\":[" + cardJson("c1", "C", "open", "", null) + "],\"members\":[]}");
        String id = body(created).get("id").asText();

        assertEquals(403, call(patch("/api/showcase-sessions/" + id + "/cards/c1"), "po", "{\"version\":1,\"decision\":\"approved\"}").getResponse().getStatus());
        assertEquals(200, call(patch("/api/showcase-sessions/" + id + "/cards/c1"), "criador", "{\"version\":1,\"decision\":\"approved\"}").getResponse().getStatus());
    }

    // ---------- PATCH por card ----------

    @Test
    void patchConflictReturnsTheCurrentCardAndStaleVersionIsRejected() throws Exception {
        String id = createSession("active", cardJson("c1", "Card 1", "open", "", null));
        String url = "/api/showcase-sessions/" + id + "/cards/c1";
        assertEquals(200, call(patch(url), "dev", "{\"version\":1,\"title\":\"Primeiro\"}").getResponse().getStatus());

        MvcResult conflict = call(patch(url), "criador", "{\"version\":1,\"title\":\"Tarde demais\"}");

        assertEquals(409, conflict.getResponse().getStatus());
        JsonNode b = body(conflict);
        assertEquals("CARD_VERSION_CONFLICT", b.get("code").asText());
        assertEquals("Primeiro", b.get("card").get("title").asText());
        assertEquals(2, b.get("card").get("version").asInt());
        assertEquals("Primeiro", card(fetch(id), "c1").get("title").asText());

        // Reaplicando sobre a versão atual, passa.
        assertEquals(200, call(patch(url), "criador", "{\"version\":2,\"title\":\"Tarde demais\"}").getResponse().getStatus());
    }

    @Test
    void patchValidation() throws Exception {
        String id = createSession("active", cardJson("c1", "Card 1", "open", "", null));

        assertEquals(400, call(patch("/api/showcase-sessions/" + id + "/cards/c1"), "dev", "{\"title\":\"sem version\"}").getResponse().getStatus());
        assertEquals(404, call(patch("/api/showcase-sessions/" + id + "/cards/naoexiste"), "dev", "{\"version\":1}").getResponse().getStatus());
        assertEquals(404, call(patch("/api/showcase-sessions/naoexiste/cards/c1"), "dev", "{\"version\":1}").getResponse().getStatus());
        String outra = createSession("active", cardJson("c-outra", "De outra Review", "open", "", null));
        assertEquals(404, call(patch("/api/showcase-sessions/" + id + "/cards/c-outra"), "dev", "{\"version\":1}").getResponse().getStatus(),
                "o card tem que ser desta Review");
        assertNotNull(outra);
        MvcResult noAuth = mockMvc.perform(patch("/api/showcase-sessions/" + id + "/cards/c1").contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}")).andReturn();
        assertEquals(401, noAuth.getResponse().getStatus());
    }

    @Test
    void patchMergesEvidencePerFieldAndKeepsOtherCardsAndAttachmentsUntouched() throws Exception {
        String id = createSession("active", cardJson("c1", "Card 1", "open", "", null), cardJson("c2", "Card 2", "open", "", null));
        jdbc.update("INSERT INTO showcase_task_files (id, session_id, task_id, original_name, content_type, size_bytes, storage_key, created_at) "
                + "VALUES ('f1', ?, 'c1', 'print.png', 'image/png', 10, 'k1', now())", id);

        MvcResult r = call(patch("/api/showcase-sessions/" + id + "/cards/c1"), "dev",
                "{\"version\":1,\"evidence\":{\"solution\":\"Nova solução\"},\"metrics\":[{\"field\":\"Economia\",\"value\":42.5}]}");

        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode c1 = card(fetch(id), "c1");
        assertEquals("Nova solução", c1.get("evidence").get("solution").asText());
        assertEquals("P", c1.get("evidence").get("problem").asText());
        assertEquals(42.5, c1.get("metrics").get(0).get("value").asDouble());
        assertEquals(1, c1.get("attachments").size(), "anexo do card continua lá");
        assertEquals(1, card(fetch(id), "c2").get("version").asInt(), "o outro card não foi tocado");
        assertEquals(1, body(r).get("attachments").size());
    }

    @Test
    void simultaneousPatchesOnDifferentCardsDoNotOverwriteEachOther() throws Exception {
        String id = createSession("active", cardJson("c1", "Card 1", "open", "", null), cardJson("c2", "Card 2", "open", "", null));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> a = () -> {
                start.await();
                return call(patch("/api/showcase-sessions/" + id + "/cards/c1"), "dev", "{\"version\":1,\"title\":\"Editado por dev\"}").getResponse().getStatus();
            };
            Callable<Integer> b = () -> {
                start.await();
                return call(patch("/api/showcase-sessions/" + id + "/cards/c2"), "criador", "{\"version\":1,\"title\":\"Editado pelo criador\"}").getResponse().getStatus();
            };
            Future<Integer> fa = pool.submit(a);
            Future<Integer> fb = pool.submit(b);
            start.countDown();
            assertEquals(200, fa.get());
            assertEquals(200, fb.get());
        } finally {
            pool.shutdownNow();
        }

        JsonNode s = fetch(id);
        assertEquals("Editado por dev", card(s, "c1").get("title").asText());
        assertEquals("Editado pelo criador", card(s, "c2").get("title").asText());
    }

    @Test
    void simultaneousPatchesOnTheSameCardExactlyOneWins() throws Exception {
        String id = createSession("active", cardJson("c1", "Card 1", "open", "", null));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> fs = new ArrayList<>();
            for (String who : new String[]{"dev", "criador"}) {
                fs.add(pool.submit(() -> {
                    start.await();
                    return call(patch("/api/showcase-sessions/" + id + "/cards/c1"), who, "{\"version\":1,\"title\":\"Por " + who + "\"}").getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> f : fs) statuses.add(f.get());
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, statuses.stream().filter(s -> s == 200).count(), statuses.toString());
        assertEquals(1, statuses.stream().filter(s -> s == 409).count(), statuses.toString());
        assertEquals(2, card(fetch(id), "c1").get("version").asInt());
    }

    @Test
    void patchRenewsTheSessionActivityTimestamp() throws Exception {
        String id = createSession("active", cardJson("c1", "Card 1", "open", "", null));
        jdbc.update("UPDATE showcase_sessions SET updated_at = now() - interval '20 hours' WHERE id = ?", id);

        call(patch("/api/showcase-sessions/" + id + "/cards/c1"), "dev", "{\"version\":1,\"title\":\"Ainda vivo\"}");

        Long ageSeconds = jdbc.queryForObject("SELECT extract(epoch FROM (now() - updated_at))::bigint FROM showcase_sessions WHERE id = ?", Long.class, id);
        assertTrue(ageSeconds < 120, "a Review editada não pode parecer abandonada: " + ageSeconds);
    }

    // ---------- mescla do POST antigo ----------

    @Test
    void staleWholeSessionPostKeepsTheCardOthersChanged() throws Exception {
        String id = createSession("active", cardJson("c1", "Card 1", "open", "", null), cardJson("c2", "Card 2", "open", "", null));
        // Cliente antigo leu v1 dos dois cards...
        JsonNode leitura = fetch(id);
        assertEquals(1, card(leitura, "c1").get("version").asInt());
        // ...enquanto outra pessoa editou o c1 por PATCH (v2).
        assertEquals(200, call(patch("/api/showcase-sessions/" + id + "/cards/c1"), "criador", "{\"version\":1,\"title\":\"Edição da outra pessoa\"}").getResponse().getStatus());

        // O cliente antigo grava a sessão inteira com a cópia velha do c1 (alterada por ele) e uma edição legítima no c2.
        MvcResult r = call(post("/api/showcase-sessions"), "dev", sessionJson(id, "active",
                cardJson("c1", "Cópia velha do cliente", "open", "", 1L), cardJson("c2", "Card 2 do cliente", "open", "", 1L)));

        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        assertEquals("c1", body(r).get("staleCardIds").get(0).asText());
        assertEquals("Edição da outra pessoa", card(body(r), "c1").get("title").asText(), "a resposta já traz o card do servidor");
        JsonNode s = fetch(id);
        assertEquals("Edição da outra pessoa", card(s, "c1").get("title").asText());
        assertEquals(2, card(s, "c1").get("version").asInt());
        assertEquals("Card 2 do cliente", card(s, "c2").get("title").asText());
        assertEquals(2, card(s, "c2").get("version").asInt());
        assertEquals(List.of("c1", "c2"), List.of(s.get("tasks").get(0).get("id").asText(), s.get("tasks").get(1).get("id").asText()), "a ordem do corpo vale");
    }

    @Test
    void keptServerCardKeepsItsMetricsEvidenceAndAttachments() throws Exception {
        String id = createSession("active", cardJson("c1", "Card 1", "open", "", null));
        call(patch("/api/showcase-sessions/" + id + "/cards/c1"), "criador",
                "{\"version\":1,\"evidence\":{\"timeSpent\":7200},\"metrics\":[{\"field\":\"Horas\",\"value\":3}]}");
        jdbc.update("INSERT INTO showcase_task_files (id, session_id, task_id, original_name, content_type, size_bytes, storage_key, created_at) "
                + "VALUES ('f1', ?, 'c1', 'print.png', 'image/png', 10, 'k1', now())", id);

        call(post("/api/showcase-sessions"), "dev", sessionJson(id, "active", cardJson("c1", "Velho", "open", "", 1L)));

        JsonNode c1 = card(fetch(id), "c1");
        assertEquals("Card 1", c1.get("title").asText());
        assertEquals(7200.0, c1.get("evidence").get("timeSpent").asDouble());
        assertEquals("Horas", c1.get("metrics").get(0).get("field").asText());
        assertEquals(1, c1.get("attachments").size());
    }

    // ---------- participantes ----------

    @Test
    void finishingStoresWhoWasOnlineAndRestoresNothingForOldOrUnpresentReviews() throws Exception {
        String id = createSession("active", cardJson("c1", "Card 1", "open", "", null));
        WebSocketSession ana = socket("p1", "po", id, "&presence=1");
        WebSocketSession devAntigo = socket("p2", "dev", id, "");
        socket("p3", "dev", id, "&presence=1"); // segunda aba do dev: uma presença só
        try {
            MvcResult r = call(post("/api/showcase-sessions"), "criador", sessionJson(id, "finished", cardJson("c1", "Card 1", "open", "", 1L)));
            assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());

            JsonNode participants = body(r).get("participants");
            assertEquals(List.of("po", "dev", "criador"), List.of(participants.get(0).get("userId").asText(),
                    participants.get(1).get("userId").asText(), participants.get(2).get("userId").asText()),
                    "online na ordem de entrada, depois quem finalizou");
            assertEquals("Pessoa po", participants.get(0).get("displayName").asText());
            JsonNode persisted = fetch(id).get("participants");
            assertEquals(3, persisted.size());
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM showcase_participants WHERE session_id = ?", Integer.class, id));

            // Finalizar de novo (já finalizada) não recalcula; reabrir zera.
            call(post("/api/showcase-sessions"), "criador", sessionJson(id, "finished", cardJson("c1", "Card 1", "open", "", 1L)));
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM showcase_participants WHERE session_id = ?", Integer.class, id));
            call(post("/api/showcase-sessions"), "criador", sessionJson(id, "planning", cardJson("c1", "Card 1", "open", "", 1L)));
            assertEquals(0, fetch(id).get("participants").size());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM showcase_participants WHERE session_id = ?", Integer.class, id));
        } finally {
            for (WebSocketSession s : List.of(ana, devAntigo)) webSocketHandler.afterConnectionClosed(s, org.springframework.web.socket.CloseStatus.NORMAL);
        }
    }

    @Test
    void finishingWithoutAnyPresenceLeavesTheListEmpty() throws Exception {
        String id = createSession("active", cardJson("c1", "Card 1", "open", "", null));

        MvcResult r = call(post("/api/showcase-sessions"), "criador", sessionJson(id, "finished", cardJson("c1", "Card 1", "open", "", 1L)));

        assertEquals(200, r.getResponse().getStatus());
        assertEquals(0, body(r).get("participants").size());
        assertEquals(0, fetch(id).get("participants").size());
        assertEquals("finished", fetch(id).get("status").asText());
    }

    // ---------- apresentador + eventos opt-in ----------

    @Test
    void presenterPermissionsStateAndOptInEvents() throws Exception {
        String id = createSession("active", cardJson("c1", "Card 1", "open", "", null));
        WebSocketSession novo = socket("w1", "dev", id, "&presence=1");
        WebSocketSession antigo = socket("w2", "po", id, "");
        try {
            String url = "/api/showcase-sessions/" + id + "/presenter";

            assertEquals(403, call(put(url), "dev", "{\"cardId\":\"c1\",\"step\":\"card\"}").getResponse().getStatus(), "participante comum");
            assertEquals(403, call(put(url), "fora", "{}").getResponse().getStatus(), "PO de outra squad");
            assertEquals(204, call(get(url), "dev", null).getResponse().getStatus(), "ninguém apresentando");

            MvcResult ok = call(put(url), "criador", "{\"cardId\":\"c1\",\"step\":\"card\"}");
            assertEquals(200, ok.getResponse().getStatus(), ok.getResponse().getContentAsString());
            assertEquals("criador", body(ok).get("userId").asText());
            assertEquals("Pessoa criador", body(ok).get("displayName").asText());
            assertEquals("c1", body(ok).get("cardId").asText());

            // Leitura: endpoint e campo da sessão (qualquer participante).
            assertEquals("c1", body(call(get(url), "dev", null)).get("cardId").asText());
            assertEquals("criador", fetch(id).get("presenter").get("userId").asText());

            // Cliente novo recebe PRESENTER_UPDATED; o antigo não recebe nada com esse tipo.
            List<JsonNode> pu = ofType(received(novo), "PRESENTER_UPDATED");
            assertEquals(1, pu.size());
            assertEquals(id, pu.get(0).get("sessionId").asText());
            assertEquals("c1", pu.get(0).get("payload").get("presenter").get("cardId").asText());
            assertEquals(0, ofType(received(antigo), "PRESENTER_UPDATED").size());

            // Liderança da squad, ADMIN e o apresentador atual (mesmo que seja um dev) publicam.
            assertEquals(200, call(put(url), "am", "{\"step\":\"cover\"}").getResponse().getStatus());
            assertEquals(200, call(put(url), "adm", "{\"step\":\"summary\"}").getResponse().getStatus());
            assertEquals(200, call(put(url), "am", "{\"cardId\":\"c1\"}").getResponse().getStatus());

            // Card de outra Review: 404; step inválido: 400.
            String outra = createSession("active", cardJson("c-outra", "Outra", "open", "", null));
            assertEquals(404, call(put(url), "criador", "{\"cardId\":\"c-outra\"}").getResponse().getStatus());
            assertEquals(400, call(put(url), "criador", "{\"step\":\"com espaço\"}").getResponse().getStatus());
            assertNotNull(outra);

            // Encerrar: DELETE por quem controla; o participante comum não.
            assertEquals(403, call(delete(url), "dev", null).getResponse().getStatus());
            assertEquals(204, call(delete(url), "criador", null).getResponse().getStatus());
            assertEquals(204, call(delete(url), "criador", null).getResponse().getStatus(), "idempotente");
            assertFalse(fetch(id).has("presenter"));
            List<JsonNode> all = ofType(received(novo), "PRESENTER_UPDATED");
            assertTrue(all.get(all.size() - 1).get("payload").get("presenter").isNull(), "o último evento limpa o apresentador");
            assertEquals(0, ofType(received(antigo), "PRESENTER_UPDATED").size());
        } finally {
            for (WebSocketSession s : List.of(novo, antigo)) webSocketHandler.afterConnectionClosed(s, org.springframework.web.socket.CloseStatus.NORMAL);
        }
    }

    @Test
    void finishingClearsThePresenterAndTellsOnlyOptInClients() throws Exception {
        String id = createSession("active", cardJson("c1", "Card 1", "open", "", null));
        call(put("/api/showcase-sessions/" + id + "/presenter"), "criador", "{\"cardId\":\"c1\"}");
        WebSocketSession novo = socket("w1", "dev", id, "&presence=1");
        WebSocketSession antigo = socket("w2", "po", id, "");
        try {
            MvcResult r = call(post("/api/showcase-sessions"), "criador", sessionJson(id, "finished", cardJson("c1", "Card 1", "open", "", 1L)));
            assertEquals(200, r.getResponse().getStatus());

            assertFalse(fetch(id).has("presenter"));
            assertEquals(409, call(put("/api/showcase-sessions/" + id + "/presenter"), "criador", "{}").getResponse().getStatus(), "finalizada");
            List<JsonNode> pu = ofType(received(novo), "PRESENTER_UPDATED");
            assertEquals(1, pu.size());
            assertTrue(pu.get(0).get("payload").get("presenter").isNull());
            assertEquals(0, ofType(received(antigo), "PRESENTER_UPDATED").size());
            assertEquals(1, ofType(received(antigo), "SESSION_UPDATED").size(), "o evento que o cliente antigo conhece continua indo");
        } finally {
            for (WebSocketSession s : List.of(novo, antigo)) webSocketHandler.afterConnectionClosed(s, org.springframework.web.socket.CloseStatus.NORMAL);
        }
    }

    @Test
    void patchEventsCardUpdatedForOptInAndSessionUpdatedForLegacy() throws Exception {
        String id = createSession("active", cardJson("c1", "Card 1", "open", "", null));
        WebSocketSession novo = socket("w1", "dev", id, "&presence=1");
        WebSocketSession antigo = socket("w2", "po", id, "");
        try {
            MvcResult r = call(patch("/api/showcase-sessions/" + id + "/cards/c1"), "criador", "{\"version\":1,\"title\":\"Editado\"}");
            assertEquals(200, r.getResponse().getStatus());

            List<JsonNode> cardEvents = ofType(received(novo), "CARD_UPDATED");
            assertEquals(1, cardEvents.size());
            JsonNode payload = cardEvents.get(0).get("payload");
            assertEquals("c1", payload.get("cardId").asText());
            assertEquals(2, payload.get("version").asInt());
            assertEquals("Editado", payload.get("card").get("title").asText());
            assertEquals(0, ofType(received(novo), "SESSION_UPDATED").size());

            assertEquals(0, ofType(received(antigo), "CARD_UPDATED").size());
            List<JsonNode> legacy = ofType(received(antigo), "SESSION_UPDATED");
            assertEquals(1, legacy.size());
            assertEquals("Editado", card(legacy.get(0).get("payload"), "c1").get("title").asText());
        } finally {
            for (WebSocketSession s : List.of(novo, antigo)) webSocketHandler.afterConnectionClosed(s, org.springframework.web.socket.CloseStatus.NORMAL);
        }
    }

    // ---------- JQL por squad ----------

    @Test
    void jqlAccessByLinkPrivacyAndDeletion() throws Exception {
        String url = "/api/showcase-sessions/jql";

        // Quem não lê a squad não lista nem grava.
        assertEquals(403, call(get(url + "?squadId=SQ1"), "fora", null).getResponse().getStatus());
        assertEquals(403, call(post(url), "fora", "{\"squadId\":\"SQ1\",\"name\":\"x\",\"jql\":\"y\"}").getResponse().getStatus());
        assertEquals(400, call(get(url), "dev", null).getResponse().getStatus());

        MvcResult shared = call(post(url), "dev", "{\"squadId\":\"sq1\",\"name\":\"Review da sprint\",\"jql\":\"sprint in openSprints()\",\"visibility\":\"SQUAD\"}");
        assertEquals(200, shared.getResponse().getStatus(), shared.getResponse().getContentAsString());
        String sharedId = body(shared).get("id").asText();
        assertEquals("SQ1", body(shared).get("squadId").asText());
        assertEquals("Pessoa dev", body(shared).get("createdByName").asText());
        MvcResult mine = call(post(url), "criador", "{\"squadId\":\"SQ1\",\"name\":\"Minha\",\"jql\":\"assignee = currentUser()\"}");
        assertEquals("PRIVATE", body(mine).get("visibility").asText(), "sem visibility = privada");
        String privateId = body(mine).get("id").asText();

        // A squad vê a compartilhada; a privada só o criador (nem a liderança vê).
        JsonNode asPo = body(call(get(url + "?squadId=SQ1"), "po", null));
        assertEquals(1, asPo.size());
        assertEquals(sharedId, asPo.get(0).get("id").asText());
        assertFalse(asPo.get(0).get("mine").asBoolean());
        assertTrue(asPo.get(0).get("canDelete").asBoolean(), "PO tem liderança na squad");
        JsonNode asDev = body(call(get(url + "?squadId=SQ1"), "dev", null));
        assertEquals(1, asDev.size());
        assertTrue(asDev.get(0).get("mine").asBoolean());
        JsonNode asCriador = body(call(get(url + "?squadId=SQ1"), "criador", null));
        assertEquals(2, asCriador.size());
        assertEquals(1, body(call(get(url + "?squadId=SQ1"), "am", null)).size(), "liderança não enxerga a privada de outra pessoa");

        // Duplicada: 409.
        assertEquals(409, call(post(url), "dev", "{\"squadId\":\"SQ1\",\"name\":\"REVIEW DA SPRINT\",\"jql\":\"x\"}").getResponse().getStatus());

        // Apagar: dev comum não apaga a compartilhada de outro; privada alheia parece não existir; liderança/criador/ADMIN apagam.
        assertEquals(403, call(delete(url + "/" + sharedId), "criador", null).getResponse().getStatus());
        assertEquals(404, call(delete(url + "/" + privateId), "am", null).getResponse().getStatus());
        assertEquals(404, call(delete(url + "/" + privateId), "dev", null).getResponse().getStatus());
        assertEquals(204, call(delete(url + "/" + sharedId), "am", null).getResponse().getStatus(), "liderança apaga a da squad");
        assertEquals(204, call(delete(url + "/" + privateId), "criador", null).getResponse().getStatus());
        assertEquals(404, call(delete(url + "/" + privateId), "criador", null).getResponse().getStatus());
        assertEquals(0, body(call(get(url + "?squadId=SQ1"), "dev", null)).size());

        MvcResult byAdmin = call(post(url), "dev", "{\"squadId\":\"SQ1\",\"name\":\"Privada do dev\",\"jql\":\"x\"}");
        assertEquals(204, call(delete(url + "/" + body(byAdmin).get("id").asText()), "adm", null).getResponse().getStatus(), "ADMIN apaga até a privada");
    }

    @Test
    void jqlIsScopedToTheSquadAndNeverExecuted() throws Exception {
        String url = "/api/showcase-sessions/jql";
        call(post(url), "po", "{\"squadId\":\"SQ1\",\"name\":\"Da SQ1\",\"jql\":\"project = SQ1\",\"visibility\":\"SQUAD\"}");
        call(post(url), "fora", "{\"squadId\":\"SQ2\",\"name\":\"Da SQ2\",\"jql\":\"project = SQ2\",\"visibility\":\"SQUAD\"}");

        JsonNode sq1 = body(call(get(url + "?squadId=SQ1"), "dev", null));
        JsonNode sq2 = body(call(get(url + "?squadId=SQ2"), "fora", null));

        assertEquals(1, sq1.size());
        assertEquals("Da SQ1", sq1.get(0).get("name").asText());
        assertEquals(1, sq2.size());
        assertEquals("Da SQ2", sq2.get(0).get("name").asText());
        assertEquals(403, call(get(url + "?squadId=SQ2"), "dev", null).getResponse().getStatus());
    }
}
