package com.agilespace.backend.service;

import com.agilespace.backend.security.JwtTokenUtil;
import com.agilespace.backend.websocket.PresenceModule;
import com.agilespace.backend.websocket.PresenceRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Cerimônia do Brainstorming (V80) contra um Postgres real, pela API HTTP completa (Tomcat simulado, JWT real, JPA, Flyway):
 * a migração idempotente sobre uma base com murais/ideias antigos, o fluxo encerrar/reabrir, assumir/passar a facilitação,
 * o limite de votos sob concorrência, a ausência de vazamento de autor em mural anônimo/oculto, a marca "editada" gravada
 * a partir do token e a retrocompatibilidade com os corpos antigos. Sem Docker o teste é ignorado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class BrainstormingCeremonyIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.brainstorming.facilitator-offline-seconds", () -> "1");
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenUtil jwtTokenUtil;
    @Autowired private PresenceRegistry presence;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE brainstorming_ideas, brainstorming_groups, brainstorming_participants, brainstorming_boards CASCADE");
    }

    // ---------- fixtures ----------

    private String token(String id, String role) {
        return "Bearer " + jwtTokenUtil.generateToken(id, id + "@totvs.com.br", "Pessoa " + id, role, null, null, null);
    }

    private String ana() { return token("ana", "MEMBER"); }

    private String bia() { return token("bia", "MEMBER"); }

    private String caio() { return token("caio", "MEMBER"); }

    private String adm() { return token("adm", "ADMIN"); }

    private MvcResult call(MockHttpServletRequestBuilder request, String auth, String body) throws Exception {
        MockHttpServletRequestBuilder b = request.header("Authorization", auth);
        if (body != null) {
            b = b.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(b).andReturn();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString());
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    /** Ana cria o mural com o corpo de um cliente antigo (só título e squad) e todo mundo entra na sala. */
    private String boardWith(String createBody, String... joiners) throws Exception {
        MvcResult created = call(post("/api/brainstormings"), ana(), createBody);
        assertEquals(201, status(created), created.getResponse().getContentAsString());
        String id = body(created).path("id").asText();
        join(id, ana(), "Ana");
        for (String who : joiners) {
            join(id, token(who, "MEMBER"), who.substring(0, 1).toUpperCase() + who.substring(1));
        }
        return id;
    }

    private void join(String boardId, String auth, String nickname) throws Exception {
        assertEquals(201, status(call(post("/api/brainstormings/" + boardId + "/participants"), auth, "{\"nickname\":\"" + nickname + "\"}")));
    }

    private String newIdea(String boardId, String auth, String text) throws Exception {
        MvcResult r = call(post("/api/brainstormings/" + boardId + "/ideas"), auth, "{\"content\":\"" + text + "\"}");
        assertEquals(201, status(r), r.getResponse().getContentAsString());
        return body(r).path("id").asText();
    }

    private void runV80() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(new ClassPathResource("db/migration/V80__brainstorming_ceremony.sql"), StandardCharsets.UTF_8));
        }
    }

    // ---------- migração ----------

    @Test
    void v80RunsTwiceOnABaseWithOldBoardsAndIdeasAndTheApiTreatsThemAsOpen() throws Exception {
        try {
            jdbc.execute("ALTER TABLE brainstorming_boards DROP COLUMN status, DROP COLUMN finished_at, DROP COLUMN facilitator_id, DROP COLUMN votes_per_participant");
            jdbc.execute("ALTER TABLE brainstorming_ideas DROP COLUMN edited_at, DROP COLUMN edited_by");
            jdbc.update("INSERT INTO brainstorming_boards (id, creator_id, title, team, created_at, phase, settings, participant_ids, timer) "
                    + "VALUES ('old-1', 'ana', 'Mural antigo', 'SQ1', '2026-01-01T10:00:00Z', 'grouping', '{\"isAnonymous\":false}'::jsonb, '[\"ana\"]'::jsonb, "
                    + "'{\"status\":\"stopped\",\"endTime\":null,\"initialDuration\":600,\"remainingOnPause\":600}'::jsonb)");
            jdbc.update("INSERT INTO brainstorming_ideas (id, board_id, content, author_id, created_at, votes, position) "
                    + "VALUES ('old-i1', 'old-1', 'Ideia velha', 'bia', '2026-01-01T10:05:00Z', '[\"ana\",\"caio\"]'::jsonb, '{\"x\":1,\"y\":2}'::jsonb)");
            jdbc.update("INSERT INTO brainstorming_participants (db_id, user_id, board_id, nickname, role, is_creator, last_active) "
                    + "VALUES ('old-1_ana', 'ana', 'old-1', 'Ana', 'Dev', TRUE, '2026-01-01T10:00:00Z')");

            runV80();
            runV80(); // idempotente
        } catch (Throwable t) {
            runV80();
            throw t;
        }

        Map<String, Object> board = jdbc.queryForMap("SELECT title, phase, status, finished_at, facilitator_id, votes_per_participant FROM brainstorming_boards WHERE id = 'old-1'");
        assertEquals("Mural antigo", board.get("title"));
        assertEquals("grouping", board.get("phase"));
        assertNull(board.get("status"));
        assertNull(board.get("finished_at"));
        assertNull(board.get("facilitator_id"));
        assertNull(board.get("votes_per_participant"));
        Map<String, Object> idea = jdbc.queryForMap("SELECT content, votes::text AS votes, edited_at, edited_by FROM brainstorming_ideas WHERE id = 'old-i1'");
        assertEquals("Ideia velha", idea.get("content"));
        assertEquals("[\"ana\", \"caio\"]", idea.get("votes"));
        assertNull(idea.get("edited_at"));
        assertNull(idea.get("edited_by"));

        // A API trata o mural antigo como aberto, facilitado pelo criador, sem limite, e a ideia sem "editada".
        JsonNode api = body(call(get("/api/brainstormings/old-1"), bia(), null));
        assertEquals("OPEN", api.path("status").asText());
        assertTrue(api.path("finishedAt").isNull());
        assertEquals("ana", api.path("facilitatorId").asText());
        assertEquals("Ana", api.path("facilitatorName").asText());
        assertTrue(api.path("votesPerParticipant").isNull());
        JsonNode ideas = body(call(get("/api/brainstormings/old-1/ideas"), bia(), null));
        assertEquals("Ideia velha", ideas.get(0).path("content").asText());
        assertFalse(ideas.get(0).has("authorName"), "o autor antigo não está na lista de participantes: sem nome, sem erro");
        assertFalse(ideas.get(0).has("editedAt"));
        // e o fluxo antigo continua: votar sem limite e encerrar funcionam sobre a linha antiga
        assertEquals(200, status(call(post("/api/brainstormings/old-1/ideas/old-i1/vote"), bia(), null)));
        assertEquals(200, status(call(post("/api/brainstormings/old-1/finish"), ana(), null)));
        assertEquals("FINISHED", jdbc.queryForObject("SELECT status FROM brainstorming_boards WHERE id = 'old-1'", String.class));
    }

    // ---------- encerrar / reabrir ----------

    @Test
    void finishAndReopenFlowOverHttp() throws Exception {
        String id = boardWith("{\"title\":\"Sessão\",\"team\":\"SQ1\"}", "bia");
        String idea = newIdea(id, bia(), "Primeira");

        JsonNode created = body(call(get("/api/brainstormings/" + id), bia(), null));
        assertEquals("OPEN", created.path("status").asText());
        assertEquals("ana", created.path("facilitatorId").asText());
        assertEquals("Ana", created.path("facilitatorName").asText());

        assertEquals(403, status(call(post("/api/brainstormings/" + id + "/finish"), bia(), null)));
        assertEquals(403, status(call(post("/api/brainstormings/" + id + "/reopen"), bia(), null)));
        assertEquals(404, status(call(post("/api/brainstormings/nao-existe/finish"), ana(), null)));
        assertEquals(401, mockMvc.perform(post("/api/brainstormings/" + id + "/finish")).andReturn().getResponse().getStatus());

        MvcResult finished = call(post("/api/brainstormings/" + id + "/finish"), ana(), null);
        assertEquals(200, status(finished));
        assertEquals("FINISHED", body(finished).path("status").asText());
        String finishedAt = body(finished).path("finishedAt").asText();
        assertFalse(finishedAt.isBlank());
        // idempotente: mesmo finishedAt
        assertEquals(finishedAt, body(call(post("/api/brainstormings/" + id + "/finish"), adm(), null)).path("finishedAt").asText());

        // leitura continua; escritas 409 com a mensagem
        assertEquals(200, status(call(get("/api/brainstormings/" + id + "/ideas"), caio(), null)));
        assertEquals(200, status(call(get("/api/brainstormings/" + id + "/participants"), caio(), null)));
        MvcResult blocked = call(post("/api/brainstormings/" + id + "/ideas"), bia(), "{\"content\":\"Nova\"}");
        assertEquals(409, status(blocked));
        assertEquals("Este brainstorming foi encerrado. Peça ao facilitador para reabri-lo.", body(blocked).path("message").asText());
        assertEquals(409, status(call(post("/api/brainstormings/" + id + "/ideas/" + idea + "/vote"), bia(), null)));
        assertEquals(409, status(call(patch("/api/brainstormings/" + id + "/ideas/" + idea), bia(), "{\"position\":{\"x\":5,\"y\":5}}")));
        assertEquals(409, status(call(post("/api/brainstormings/" + id + "/groups"), bia(), "{\"title\":\"G\"}")));
        assertEquals(409, status(call(delete("/api/brainstormings/" + id + "/ideas/" + idea), ana(), null)));
        assertEquals(409, status(call(patch("/api/brainstormings/" + id), ana(), "{\"phase\":\"grouping\"}")));
        assertEquals(200, status(call(patch("/api/brainstormings/" + id), ana(), "{\"title\":\"Renomeado\"}")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM brainstorming_ideas WHERE board_id = ?", Integer.class, id));

        MvcResult reopened = call(post("/api/brainstormings/" + id + "/reopen"), ana(), null);
        assertEquals(200, status(reopened));
        assertEquals("OPEN", body(reopened).path("status").asText());
        assertTrue(body(reopened).path("finishedAt").isNull());
        assertEquals(201, status(call(post("/api/brainstormings/" + id + "/ideas"), bia(), "{\"content\":\"Agora pode\"}")));
    }

    // ---------- facilitação ----------

    @Test
    void claimNeedsAnOfflineFacilitatorAndTransferMovesThePowers() throws Exception {
        String id = boardWith("{\"title\":\"Sessão\",\"team\":\"SQ1\"}", "bia", "caio");
        presence.join(PresenceModule.BRAINSTORMING, id, "ana", "ws-ana", null);

        MvcResult online = call(post("/api/brainstormings/" + id + "/facilitation/claim"), bia(), null);
        assertEquals(409, status(online));
        assertEquals("O facilitador está online.", body(online).path("message").asText());

        presence.leave(PresenceModule.BRAINSTORMING, id, "ana", "ws-ana");
        assertEquals(409, status(call(post("/api/brainstormings/" + id + "/facilitation/claim"), bia(), null)), "saiu há instantes");
        Thread.sleep(1300);

        // quem nunca entrou na sala não assume
        assertEquals(403, status(call(post("/api/brainstormings/" + id + "/facilitation/claim"), token("estranho", "MEMBER"), null)));

        MvcResult claimed = call(post("/api/brainstormings/" + id + "/facilitation/claim"), bia(), null);
        assertEquals(200, status(claimed));
        assertEquals("bia", body(claimed).path("facilitatorId").asText());
        assertEquals("Bia", body(claimed).path("facilitatorName").asText());
        assertEquals("ana", body(claimed).path("creatorId").asText());
        assertEquals("bia", jdbc.queryForObject("SELECT facilitator_id FROM brainstorming_boards WHERE id = ?", String.class, id));

        // poderes seguem o facilitador; a criadora perde o PATCH mas ainda encerra
        assertEquals(200, status(call(patch("/api/brainstormings/" + id), bia(), "{\"phase\":\"diagram\"}")));
        assertEquals(403, status(call(patch("/api/brainstormings/" + id), ana(), "{\"phase\":\"grouping\"}")));
        JsonNode participants = body(call(get("/api/brainstormings/" + id + "/participants"), caio(), null));
        for (JsonNode p : participants) {
            assertEquals(p.path("id").asText().equals("bia"), p.path("facilitator").asBoolean(), p.toString());
            assertEquals(p.path("nickname").asText(), p.path("displayName").asText());
        }

        // passar: só o facilitador atual (ou ADMIN); destino precisa estar na sala
        assertEquals(403, status(call(post("/api/brainstormings/" + id + "/facilitation/transfer"), ana(), "{\"userId\":\"caio\"}")));
        assertEquals(404, status(call(post("/api/brainstormings/" + id + "/facilitation/transfer"), bia(), "{\"userId\":\"fantasma\"}")));
        assertEquals(400, status(call(post("/api/brainstormings/" + id + "/facilitation/transfer"), bia(), "{}")));
        assertEquals("caio", body(call(post("/api/brainstormings/" + id + "/facilitation/transfer"), bia(), "{\"userId\":\"caio\"}")).path("facilitatorId").asText());
        assertEquals("ana", body(call(post("/api/brainstormings/" + id + "/facilitation/transfer"), adm(), "{\"userId\":\"ana\"}")).path("facilitatorId").asText());
        // a criadora retoma sempre, mesmo com o facilitador online
        presence.join(PresenceModule.BRAINSTORMING, id, "ana", "ws-ana-2", null);
        assertEquals(200, status(call(post("/api/brainstormings/" + id + "/facilitation/transfer"), ana(), "{\"userId\":\"caio\"}")));
        presence.join(PresenceModule.BRAINSTORMING, id, "caio", "ws-caio", null);
        assertEquals(200, status(call(post("/api/brainstormings/" + id + "/facilitation/claim"), ana(), null)));
        presence.leave(PresenceModule.BRAINSTORMING, id, "ana", "ws-ana-2");
        presence.leave(PresenceModule.BRAINSTORMING, id, "caio", "ws-caio");
    }

    // ---------- limite de votos ----------

    @Test
    void voteLimitIsEnforcedAndRemovingGivesTheQuotaBack() throws Exception {
        String id = boardWith("{\"title\":\"Sessão\",\"team\":\"SQ1\",\"votesPerParticipant\":2}", "bia");
        assertEquals(2, body(call(get("/api/brainstormings/" + id), bia(), null)).path("votesPerParticipant").asInt());
        List<String> ideas = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ideas.add(newIdea(id, ana(), "Ideia " + i));
        }

        assertEquals(200, status(call(post("/api/brainstormings/" + id + "/ideas/" + ideas.get(0) + "/vote"), bia(), null)));
        assertEquals(200, status(call(post("/api/brainstormings/" + id + "/ideas/" + ideas.get(1) + "/vote"), bia(), null)));
        MvcResult third = call(post("/api/brainstormings/" + id + "/ideas/" + ideas.get(2) + "/vote"), bia(), null);
        assertEquals(409, status(third));
        assertEquals("Você já usou seus 2 votos. Remova um voto para votar em outra ideia.", body(third).path("message").asText());
        // outra pessoa tem a cota dela
        assertEquals(200, status(call(post("/api/brainstormings/" + id + "/ideas/" + ideas.get(2) + "/vote"), ana(), null)));
        // remover devolve; o limite sobe e desce pelo PATCH do facilitador (ninguém perde voto ao reduzir)
        assertEquals(200, status(call(post("/api/brainstormings/" + id + "/ideas/" + ideas.get(0) + "/vote"), bia(), null)));
        assertEquals(200, status(call(post("/api/brainstormings/" + id + "/ideas/" + ideas.get(2) + "/vote"), bia(), null)));
        assertEquals(403, status(call(patch("/api/brainstormings/" + id), bia(), "{\"votesPerParticipant\":3}")), "bia não é facilitadora");
        assertEquals(200, status(call(patch("/api/brainstormings/" + id), ana(), "{\"votesPerParticipant\":1}")));
        assertEquals(2, votesOf("bia", id), "reduzir o limite não desfaz votos já dados");
        assertEquals(409, status(call(post("/api/brainstormings/" + id + "/ideas/" + ideas.get(3) + "/vote"), bia(), null)));
        assertEquals(400, status(call(patch("/api/brainstormings/" + id), ana(), "{\"votesPerParticipant\":0}")));
        assertEquals(200, status(call(patch("/api/brainstormings/" + id), ana(), "{\"votesPerParticipant\":null}")));
        assertEquals(200, status(call(post("/api/brainstormings/" + id + "/ideas/" + ideas.get(3) + "/vote"), bia(), null)));
    }

    private int votesOf(String userId, String boardId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM brainstorming_ideas WHERE board_id = ? AND votes @> to_jsonb(?::text)", Integer.class, boardId, userId);
    }

    @Test
    void concurrentVotesOfTheSamePersonNeverExceedTheLimit() throws Exception {
        String id = boardWith("{\"title\":\"Sessão\",\"team\":\"SQ1\",\"votesPerParticipant\":3}", "bia", "caio");
        List<String> ideas = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ideas.add(newIdea(id, ana(), "Ideia " + i));
        }
        String bia = bia();
        String caio = caio();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<int[]>> results = new ArrayList<>();
            for (String idea : ideas) {
                for (String auth : List.of(bia, caio)) {
                    results.add(pool.submit(() -> {
                        start.await();
                        return new int[]{auth.equals(bia) ? 0 : 1,
                                status(call(post("/api/brainstormings/" + id + "/ideas/" + idea + "/vote"), auth, null))};
                    }));
                }
            }
            start.countDown();
            int[] ok = new int[2];
            int[] conflict = new int[2];
            for (Future<int[]> f : results) {
                int[] r = f.get();
                if (r[1] == 200) ok[r[0]]++;
                else if (r[1] == 409) conflict[r[0]]++;
                else fail("status inesperado " + r[1]);
            }
            assertEquals(3, ok[0], "bia: exatamente o limite");
            assertEquals(5, conflict[0]);
            assertEquals(3, ok[1], "caio: exatamente o limite, independente da bia");
            assertEquals(5, conflict[1]);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(3, votesOf("bia", id));
        assertEquals(3, votesOf("caio", id));
    }

    // ---------- autoria / anonimato ----------

    @Test
    void openBoardExposesAuthorAndEditorNamesAndEditedMarkComesFromTheToken() throws Exception {
        String id = boardWith("{\"title\":\"Sessão\",\"team\":\"SQ1\"}", "bia", "caio");
        String idea = newIdea(id, bia(), "Texto original");

        JsonNode first = body(call(get("/api/brainstormings/" + id + "/ideas"), caio(), null)).get(0);
        assertEquals("Bia", first.path("authorName").asText());
        assertEquals("bia", first.path("authorId").asText());
        assertFalse(first.has("editedAt"), "ideia nunca editada não traz a marca");

        // Caio edita o texto (padrão do mural: qualquer um edita); o corpo tenta forjar a autoria da edição
        MvcResult edited = call(patch("/api/brainstormings/" + id + "/ideas/" + idea), caio(),
                "{\"content\":\"Texto novo\",\"editedBy\":\"ana\",\"editedAt\":\"1999-01-01T00:00:00Z\",\"authorId\":\"ana\"}");
        assertEquals(200, status(edited));
        JsonNode out = body(edited);
        assertEquals("caio", out.path("editedBy").asText());
        assertEquals("Caio", out.path("editedByName").asText());
        assertEquals("Bia", out.path("authorName").asText());
        assertEquals("bia", out.path("authorId").asText());
        assertNotEquals("1999-01-01T00:00:00Z", out.path("editedAt").asText());
        assertEquals("caio", jdbc.queryForObject("SELECT edited_by FROM brainstorming_ideas WHERE id = ?", String.class, idea));
        assertNotNull(jdbc.queryForObject("SELECT edited_at FROM brainstorming_ideas WHERE id = ?", String.class, idea));

        // mover não é editar; texto igual (aba antiga) não é editar
        String before = jdbc.queryForObject("SELECT edited_at FROM brainstorming_ideas WHERE id = ?", String.class, idea);
        assertEquals(200, status(call(patch("/api/brainstormings/" + id + "/ideas/" + idea), bia(), "{\"position\":{\"x\":50,\"y\":60}}")));
        assertEquals(200, status(call(post("/api/brainstormings/" + id + "/ideas"), bia(), "{\"id\":\"" + idea + "\",\"content\":\"Texto novo\",\"position\":{\"x\":1,\"y\":1}}")));
        assertEquals(before, jdbc.queryForObject("SELECT edited_at FROM brainstorming_ideas WHERE id = ?", String.class, idea));
        assertEquals("caio", jdbc.queryForObject("SELECT edited_by FROM brainstorming_ideas WHERE id = ?", String.class, idea));
        assertEquals("bia", jdbc.queryForObject("SELECT author_id FROM brainstorming_ideas WHERE id = ?", String.class, idea));
    }

    @Test
    void anonymousBoardNeverLeaksAuthorOrEditorToAnyone() throws Exception {
        String id = boardWith("{\"title\":\"Sessão\",\"team\":\"SQ1\",\"settings\":{\"isAnonymous\":true}}", "bia", "caio");
        String idea = newIdea(id, bia(), "Segredo da Bia");
        assertEquals(200, status(call(patch("/api/brainstormings/" + id + "/ideas/" + idea), caio(), "{\"content\":\"Editado pelo Caio\"}")));

        for (String reader : List.of(ana(), adm(), caio())) {
            String raw = call(get("/api/brainstormings/" + id + "/ideas"), reader, null).getResponse().getContentAsString();
            JsonNode i = JSON.readTree(raw).get(0);
            assertTrue(i.path("authorId").isNull(), raw);
            assertFalse(i.has("authorName"), raw);
            assertFalse(i.has("editedByName"), raw);
            assertFalse(i.has("editedBy"), raw);
            assertTrue(i.has("editedAt"), raw);
            assertFalse(raw.contains("\"bia\"") || raw.contains("Bia"), raw);
        }
        // a resposta de quem editou (Caio, não autor) também não devolve a identidade do autor
        String caioView = call(post("/api/brainstormings/" + id + "/ideas/" + idea + "/vote"), caio(), null).getResponse().getContentAsString();
        assertFalse(caioView.contains("Bia") || caioView.contains("\"bia\""), caioView);
        // o autor vê o próprio id, mas não quem mais mexeu na ideia dele
        JsonNode mine = body(call(get("/api/brainstormings/" + id + "/ideas"), bia(), null)).get(0);
        assertEquals("bia", mine.path("authorId").asText());
        assertFalse(mine.has("editedBy"));
        assertFalse(mine.has("authorName"));
        assertEquals("caio", jdbc.queryForObject("SELECT edited_by FROM brainstorming_ideas WHERE id = ?", String.class, idea), "o banco guarda a verdade");
    }

    @Test
    void hiddenIdeasLeakNoNamesToPeersButTheFacilitatorAndAuthorSeeThem() throws Exception {
        String id = boardWith("{\"title\":\"Sessão\",\"team\":\"SQ1\",\"settings\":{\"isRevealed\":false}}", "bia", "caio");
        String idea = newIdea(id, bia(), "Escondida");
        assertEquals(200, status(call(patch("/api/brainstormings/" + id + "/ideas/" + idea), bia(), "{\"content\":\"Escondida e editada\"}")));

        String peer = call(get("/api/brainstormings/" + id + "/ideas"), caio(), null).getResponse().getContentAsString();
        JsonNode p = JSON.readTree(peer).get(0);
        assertEquals("", p.path("content").asText());
        assertTrue(p.path("hidden").asBoolean());
        assertFalse(peer.contains("Bia") || peer.contains("\"bia\"") || peer.contains("editedAt"), peer);

        JsonNode facilitator = body(call(get("/api/brainstormings/" + id + "/ideas"), ana(), null)).get(0);
        assertEquals("Bia", facilitator.path("authorName").asText());
        assertEquals("Escondida e editada", facilitator.path("content").asText());
        assertTrue(facilitator.has("editedAt"));
        assertEquals("Bia", body(call(get("/api/brainstormings/" + id + "/ideas"), bia(), null)).get(0).path("authorName").asText());
    }

    // ---------- quem edita / apaga ----------

    @Test
    void restrictEditsLimitsTextEditsButNotMovingVotingOrMerging() throws Exception {
        String id = boardWith("{\"title\":\"Sessão\",\"team\":\"SQ1\",\"settings\":{\"restrictEdits\":true}}", "bia", "caio");
        String a = newIdea(id, bia(), "Da Bia");
        String b = newIdea(id, bia(), "Outra da Bia");

        MvcResult denied = call(patch("/api/brainstormings/" + id + "/ideas/" + a), caio(), "{\"content\":\"Invasão\"}");
        assertEquals(403, status(denied));
        assertEquals("Só o autor da ideia ou o facilitador podem editar o texto.", body(denied).path("message").asText());
        assertEquals(403, status(call(post("/api/brainstormings/" + id + "/ideas"), caio(), "{\"id\":\"" + a + "\",\"content\":\"Invasão 2\"}")));
        assertEquals("Da Bia", jdbc.queryForObject("SELECT content FROM brainstorming_ideas WHERE id = ?", String.class, a));

        assertEquals(200, status(call(patch("/api/brainstormings/" + id + "/ideas/" + a), bia(), "{\"content\":\"Da Bia (v2)\"}")));
        assertEquals(200, status(call(patch("/api/brainstormings/" + id + "/ideas/" + a), ana(), "{\"content\":\"Da Bia (facilitadora)\"}")));
        assertEquals(200, status(call(patch("/api/brainstormings/" + id + "/ideas/" + a), adm(), "{\"content\":\"Da Bia (admin)\"}")));
        assertEquals(200, status(call(patch("/api/brainstormings/" + id + "/ideas/" + a), caio(), "{\"position\":{\"x\":7,\"y\":8}}")));
        assertEquals(200, status(call(post("/api/brainstormings/" + id + "/ideas/" + a + "/vote"), caio(), null)));
        assertEquals(200, status(call(post("/api/brainstormings/" + id + "/ideas/" + a + "/merge/" + b), caio(), null)));
        assertEquals("caio", jdbc.queryForObject("SELECT edited_by FROM brainstorming_ideas WHERE id = ?", String.class, a));
        // apagar: outra pessoa não; autor e facilitador sim
        String c = newIdea(id, bia(), "Para apagar");
        assertEquals(403, status(call(delete("/api/brainstormings/" + id + "/ideas/" + c), caio(), null)));
        assertEquals(204, status(call(delete("/api/brainstormings/" + id + "/ideas/" + c), bia(), null)));
        String d = newIdea(id, bia(), "Para apagar 2");
        assertEquals(204, status(call(delete("/api/brainstormings/" + id + "/ideas/" + d), ana(), null)));
    }

    @Test
    void defaultBoardKeepsTodaysContractAnyoneEditsText() throws Exception {
        String id = boardWith("{\"title\":\"Sessão\",\"team\":\"SQ1\"}", "bia", "caio");
        String idea = newIdea(id, bia(), "Da Bia");
        assertEquals(200, status(call(patch("/api/brainstormings/" + id + "/ideas/" + idea), caio(), "{\"content\":\"Caio mexeu\"}")));
        assertEquals(200, status(call(post("/api/brainstormings"), ana(), "{\"id\":\"" + id + "\",\"title\":\"Corpo antigo de abas antigas\",\"phase\":\"diagram\"}")));
        assertEquals("Corpo antigo de abas antigas", body(call(get("/api/brainstormings/" + id), bia(), null)).path("title").asText());
    }

    // ---------- retrocompatibilidade ----------

    @Test
    void oldClientBodiesAndResponseShapeStillWork() throws Exception {
        MvcResult created = call(post("/api/brainstormings"), ana(),
                "{\"title\":\"Antigo\",\"team\":\"SQ1\",\"creatorId\":\"impostor\",\"status\":\"FINISHED\",\"finishedAt\":\"x\",\"facilitatorId\":\"impostor\",\"settings\":{\"isAnonymous\":false}}");
        assertEquals(201, status(created));
        JsonNode board = body(created);
        // campos de sempre
        for (String field : List.of("id", "creatorId", "title", "team", "createdAt", "phase", "settings", "timer", "participantIds")) {
            assertTrue(board.has(field), field);
        }
        assertEquals("ana", board.path("creatorId").asText());
        assertEquals("OPEN", board.path("status").asText(), "status do corpo é ignorado");
        assertTrue(board.path("finishedAt").isNull());
        assertEquals("ana", board.path("facilitatorId").asText(), "facilitador do corpo é ignorado");
        assertTrue(board.path("votesPerParticipant").isNull());
        String id = board.path("id").asText();

        // ideia nova sem nenhum campo novo
        String idea = newIdea(id, ana(), "Texto");
        JsonNode got = body(call(get("/api/brainstormings/" + id + "/ideas"), ana(), null)).get(0);
        assertEquals(idea, got.path("id").asText());
        assertTrue(got.has("votes") && got.has("position") && got.has("authorId"));
        // voto sem limite alterna como sempre
        assertEquals(1, body(call(post("/api/brainstormings/" + id + "/ideas/" + idea + "/vote"), ana(), null)).path("votes").size());
        assertEquals(0, body(call(post("/api/brainstormings/" + id + "/ideas/" + idea + "/vote"), ana(), null)).path("votes").size());
        assertEquals(1, body(call(get("/api/brainstormings?squadId=SQ1"), adm(), null)).size());
    }
}
