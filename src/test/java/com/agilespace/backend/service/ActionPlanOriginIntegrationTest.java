package com.agilespace.backend.service;

import com.agilespace.backend.security.JwtTokenUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Origem do plano de ação (V78) contra um Postgres real: a migração idempotente sobre uma base com planos existentes, o
 * índice único parcial (por squad; planos antigos fora dele) e o fluxo HTTP completo (criar com origem, 409 com
 * existingPlanId, contagens, filtro, prazo da ação, autorização da squad). Sem Docker o teste é ignorado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ActionPlanOriginIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenUtil jwtTokenUtil;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE action_plan_tasks, action_plan_participants, action_plans, project_member_roles, squad_members, projects, users CASCADE");
        project("SQ1");
        project("SQ2");
        user("adm", "ADMIN", null);
        user("ana", "MEMBER", "SQ1"); // membro só da SQ1
    }

    // ---------- fixtures ----------

    private void project(String id) {
        jdbc.update("INSERT INTO projects (id, name, tribe_name, segment_name) VALUES (?, ?, 'Tribo', 'Seg')", id, "Squad " + id);
    }

    private void user(String id, String role, String squadId) {
        jdbc.update("INSERT INTO users (id, email, name, role, is_active, is_guest, default_project_id) VALUES (?, ?, ?, ?, TRUE, FALSE, ?)",
                id, id + "@totvs.com.br", "Pessoa " + id, role, squadId);
    }

    private String token(String id, String role) {
        return "Bearer " + jwtTokenUtil.generateToken(id, id + "@totvs.com.br", "Pessoa " + id, role, null, null, null);
    }

    private String admin() { return token("adm", "ADMIN"); }

    private String ana() { return token("ana", "MEMBER"); }

    private void runV78() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(new ClassPathResource("db/migration/V78__action_plan_source.sql"), StandardCharsets.UTF_8));
        }
    }

    private void legacyPlan(String squad, String title) {
        jdbc.update("INSERT INTO action_plans (id, creator_id, title, team, squad_id, is_public) VALUES (?, 'adm', ?, ?, ?, TRUE)",
                UUID.randomUUID(), title, squad, squad);
    }

    private void sourcePlan(String squad, String type, String sourceId) {
        jdbc.update("INSERT INTO action_plans (id, creator_id, title, squad_id, is_public, source_type, source_id) VALUES (?, 'adm', 'P', ?, TRUE, ?, ?)",
                UUID.randomUUID(), squad, type, sourceId);
    }

    private MvcResult send(String url, String auth, String json) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(url).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private static String planJson(String squad, String type, String sourceId) {
        return "{\"title\":\"Plano\",\"team\":\"" + squad + "\",\"squadId\":\"" + squad + "\""
                + (type == null ? "" : ",\"sourceType\":\"" + type + "\",\"sourceId\":\"" + sourceId + "\",\"sourceTitle\":\"Origem\"") + "}";
    }

    private String idOf(MvcResult r) throws Exception {
        String body = r.getResponse().getContentAsString();
        return body.replaceAll(".*\"id\":\"([0-9a-f-]{36})\".*", "$1");
    }

    // ---------- migração ----------

    @Test
    void v78RunsTwiceOnABaseWithExistingPlansAndKeepsThemUntouched() throws Exception {
        // Volta ao estado pré-V78 (descarta só o que a V78 criou) e põe planos "antigos", dois na mesma squad.
        jdbc.execute("DROP INDEX IF EXISTS uq_action_plans_source");
        jdbc.execute("DROP INDEX IF EXISTS idx_action_plans_source");
        jdbc.execute("ALTER TABLE action_plans DROP COLUMN source_type, DROP COLUMN source_id, DROP COLUMN source_title");
        jdbc.execute("ALTER TABLE action_plan_tasks DROP COLUMN source_item_id, DROP COLUMN source_label, DROP COLUMN due_date");
        legacyPlan("SQ1", "Antigo 1");
        legacyPlan("SQ1", "Antigo 2");
        UUID oldPlan = jdbc.queryForObject("SELECT id FROM action_plans WHERE title = 'Antigo 1'", UUID.class);
        jdbc.update("INSERT INTO action_plan_tasks (id, board_id, what, when_field, status) VALUES (?, ?, 'Tarefa velha', 'semana que vem', 'todo')",
                UUID.randomUUID(), oldPlan);

        runV78();
        runV78(); // idempotente

        List<Map<String, Object>> plans = jdbc.queryForList("SELECT title, source_type, source_id, source_title FROM action_plans ORDER BY title");
        assertEquals(2, plans.size());
        plans.forEach(p -> {
            assertNull(p.get("source_type"));
            assertNull(p.get("source_id"));
            assertNull(p.get("source_title"));
        });
        Map<String, Object> task = jdbc.queryForMap("SELECT what, when_field, source_item_id, source_label, due_date FROM action_plan_tasks");
        assertEquals("Tarefa velha", task.get("what"));
        assertEquals("semana que vem", task.get("when_field"));
        assertNull(task.get("due_date"));
        List<String> indexes = jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'action_plans'", String.class);
        assertTrue(indexes.contains("uq_action_plans_source"));
        assertTrue(indexes.contains("idx_action_plans_source"));
        // Planos antigos seguem se comportando como MANUAL na API
        mockMvc.perform(get("/api/action-plans").param("squadId", "SQ1").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sourceType").value("MANUAL"))
                .andExpect(jsonPath("$[1].sourceType").value("MANUAL"));
    }

    @Test
    void partialUniqueIndexIsPerSquadAndIgnoresLegacyRows() {
        sourcePlan("SQ1", "RETRO", "r1");
        assertThrows(DataIntegrityViolationException.class, () -> sourcePlan("SQ1", "RETRO", "r1"));
        assertThrows(DataIntegrityViolationException.class, () -> sourcePlan("sq1", "RETRO", "r1"), "a chave é sem caixa, como o resto da squad");
        sourcePlan("SQ2", "RETRO", "r1");              // outra squad: pode
        sourcePlan("SQ1", "HEALTH_CHECK", "r1");       // outro tipo: pode
        sourcePlan("SQ1", "RETRO", "r2");              // outro id: pode
        legacyPlan("SQ1", "A");
        legacyPlan("SQ1", "B");                        // source_id nulo: fora do índice
        sourcePlan(null, "HEALTH_CHECK", "hc-9");
        assertThrows(DataIntegrityViolationException.class, () -> sourcePlan(null, "HEALTH_CHECK", "hc-9"), "plano sem squad também trava");
    }

    // ---------- API ----------

    @Test
    void createWithSourceThenDuplicateIs409WithExistingPlanId() throws Exception {
        MvcResult first = send("/api/action-plans", ana(), planJson("SQ1", "health_check", "hc-1"));
        assertEquals(201, first.getResponse().getStatus());
        assertTrue(first.getResponse().getContentAsString().contains("\"sourceType\":\"HEALTH_CHECK\""));
        assertTrue(first.getResponse().getContentAsString().contains("\"sourceId\":\"hc-1\""));
        assertTrue(first.getResponse().getContentAsString().contains("\"sourceTitle\":\"Origem\""));
        String firstId = idOf(first);

        MvcResult second = send("/api/action-plans", ana(), planJson("SQ1", "HEALTH_CHECK", "hc-1"));
        assertEquals(409, second.getResponse().getStatus());
        assertTrue(second.getResponse().getContentAsString().contains("\"existingPlanId\":\"" + firstId + "\""));

        // Outra squad pode ter a mesma origem (o admin cria; ana não lê a SQ2)
        assertEquals(201, send("/api/action-plans", admin(), planJson("SQ2", "HEALTH_CHECK", "hc-1")).getResponse().getStatus());
        // Apagar libera a origem
        mockMvc.perform(delete("/api/action-plans/" + firstId).header("Authorization", ana())).andExpect(status().isNoContent());
        assertEquals(201, send("/api/action-plans", ana(), planJson("SQ1", "HEALTH_CHECK", "hc-1")).getResponse().getStatus());
    }

    @Test
    void duplicatePrivatePlanOfSomeoneElseDoesNotLeakItsId() throws Exception {
        user("beto", "MEMBER", "SQ1");
        MvcResult first = send("/api/action-plans", admin(),
                "{\"title\":\"Privado\",\"team\":\"SQ1\",\"squadId\":\"SQ1\",\"isPublic\":false,\"sourceType\":\"RETRO\",\"sourceId\":\"r1\"}");
        assertEquals(201, first.getResponse().getStatus());

        MvcResult second = send("/api/action-plans", token("beto", "MEMBER"), planJson("SQ1", "RETRO", "r1"));

        assertEquals(409, second.getResponse().getStatus());
        assertFalse(second.getResponse().getContentAsString().contains("existingPlanId"));
    }

    @Test
    void concurrentCreationOfTheSameSourceYieldsOneCreatedAndOneConflict() throws Exception {
        String auth = ana();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> call = () -> send("/api/action-plans", auth, planJson("SQ1", "BRAINSTORMING", "bs-1")).getResponse().getStatus();
            Future<Integer> a = pool.submit(call);
            Future<Integer> b = pool.submit(call);
            List<Integer> statuses = List.of(a.get(), b.get()).stream().sorted().collect(Collectors.toList());
            assertEquals(List.of(201, 409), statuses);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM action_plans WHERE source_id = 'bs-1'", Integer.class));
    }

    @Test
    void validationErrorsAre400() throws Exception {
        assertEquals(400, send("/api/action-plans", ana(), planJson("SQ1", "JIRA", "x")).getResponse().getStatus());
        assertEquals(400, send("/api/action-plans", ana(), "{\"title\":\"P\",\"team\":\"SQ1\",\"squadId\":\"SQ1\",\"sourceType\":\"RETRO\"}").getResponse().getStatus());
        assertEquals(400, send("/api/action-plans", ana(), "{\"title\":\"P\",\"sourceId\":\"x\"}").getResponse().getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM action_plans", Integer.class));
    }

    @Test
    void legacyBodyStillWorksAndIsManual() throws Exception {
        MvcResult r = send("/api/action-plans", ana(), "{\"title\":\"Plano antigo\",\"team\":\"Squad Geral\"}");
        assertEquals(201, r.getResponse().getStatus());
        assertTrue(r.getResponse().getContentAsString().contains("\"sourceType\":\"MANUAL\""));
        assertFalse(r.getResponse().getContentAsString().contains("tasksTotal"), "contagens só nas listas");
        // Dois manuais iguais não colidem
        assertEquals(201, send("/api/action-plans", ana(), "{\"title\":\"Plano antigo\",\"team\":\"SQ1\",\"squadId\":\"SQ1\"}").getResponse().getStatus());
        assertEquals(201, send("/api/action-plans", ana(), "{\"title\":\"Plano antigo\",\"team\":\"SQ1\",\"squadId\":\"SQ1\"}").getResponse().getStatus());
    }

    @Test
    void creatingInASquadTheCallerCannotReadIs403() throws Exception {
        MvcResult r = send("/api/action-plans", ana(), planJson("SQ2", "RETRO", "r1"));
        assertEquals(403, r.getResponse().getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM action_plans", Integer.class));
        // Um plano de SQ2 criado pelo admin não aparece nem entra em 409 para a ana
        assertEquals(201, send("/api/action-plans", admin(), planJson("SQ2", "RETRO", "r1")).getResponse().getStatus());
        assertEquals(403, send("/api/action-plans", ana(), planJson("SQ2", "RETRO", "r1")).getResponse().getStatus());
    }

    @Test
    void listHasCountsAndFilters() throws Exception {
        String planA = idOf(send("/api/action-plans", ana(), planJson("SQ1", "HEALTH_CHECK", "hc-1")));
        String planB = idOf(send("/api/action-plans", ana(), planJson("SQ1", "RETRO", "r1")));
        send("/api/action-plans", ana(), "{\"title\":\"Manual\",\"team\":\"SQ1\",\"squadId\":\"SQ1\"}");
        for (String status : List.of("done", "done", "todo", "blocked")) {
            assertEquals(201, send("/api/action-plans/" + planA + "/tasks", ana(), "{\"what\":\"Ação\",\"status\":\"" + status + "\"}").getResponse().getStatus());
        }

        MvcResult all = mockMvc.perform(get("/api/action-plans").param("squadId", "SQ1").header("Authorization", ana()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3)).andReturn();
        String body = all.getResponse().getContentAsString();
        assertTrue(body.contains("\"tasksTotal\":4"));
        assertTrue(body.contains("\"tasksDone\":2"));
        assertTrue(body.contains("\"tasksTotal\":0"));

        mockMvc.perform(get("/api/action-plans").param("squadId", "SQ1").param("sourceType", "HEALTH_CHECK").param("sourceId", "hc-1")
                        .header("Authorization", ana()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(planA))
                .andExpect(jsonPath("$[0].tasksTotal").value(4))
                .andExpect(jsonPath("$[0].tasksDone").value(2));
        mockMvc.perform(get("/api/action-plans").param("squadId", "SQ1").param("sourceType", "retro").header("Authorization", ana()))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(planB));
        mockMvc.perform(get("/api/action-plans").param("squadId", "SQ1").param("sourceType", "MANUAL").header("Authorization", ana()))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].title").value("Manual"));
        mockMvc.perform(get("/api/action-plans").param("squadId", "SQ1").param("sourceType", "RETRO").param("sourceId", "nao-existe")
                        .header("Authorization", ana()))
                .andExpect(jsonPath("$.length()").value(0));
        // Sem a squad: acha o plano deste radar
        mockMvc.perform(get("/api/action-plans").param("sourceType", "HEALTH_CHECK").param("sourceId", "hc-1").header("Authorization", ana()))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(planA));
        mockMvc.perform(get("/api/action-plans").param("squadId", "SQ1").param("sourceType", "NOPE").header("Authorization", ana()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/action-plans").param("squadId", "SQ1").param("sourceId", "x").header("Authorization", ana()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchBySourceWithoutSquadDoesNotCrossIntoSquadsTheCallerCannotRead() throws Exception {
        send("/api/action-plans", admin(), planJson("SQ2", "HEALTH_CHECK", "hc-2"));
        mockMvc.perform(get("/api/action-plans").param("sourceType", "HEALTH_CHECK").param("sourceId", "hc-2").header("Authorization", ana()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/action-plans").param("sourceType", "HEALTH_CHECK").param("sourceId", "hc-2").header("Authorization", admin()))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void taskCarriesOriginAndDueDate() throws Exception {
        String plan = idOf(send("/api/action-plans", ana(), planJson("SQ1", "HEALTH_CHECK", "hc-1")));
        MvcResult created = send("/api/action-plans/" + plan + "/tasks", ana(),
                "{\"what\":\"Combinar canal\",\"when\":\"até o fim do mês\",\"sourceItemId\":\"comunicacao\",\"sourceLabel\":\"Comunicação\",\"dueDate\":\"2026-11-05\"}");
        assertEquals(201, created.getResponse().getStatus());
        String json = created.getResponse().getContentAsString();
        assertTrue(json.contains("\"sourceItemId\":\"comunicacao\""));
        assertTrue(json.contains("\"sourceLabel\":\"Comunicação\""));
        assertTrue(json.contains("\"dueDate\":\"2026-11-05\""));
        assertTrue(json.contains("\"when\":\"até o fim do mês\""));
        assertFalse(json.contains("clearDueDate"));
        String taskId = idOf(created);

        // Edição parcial: outro campo não mexe no prazo; dueDate novo muda; origem ignorada
        mockMvc.perform(put("/api/action-plans/tasks/" + taskId).header("Authorization", ana()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"done\",\"sourceItemId\":\"outro\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dueDate").value("2026-11-05")).andExpect(jsonPath("$.sourceItemId").value("comunicacao"));
        mockMvc.perform(put("/api/action-plans/tasks/" + taskId).header("Authorization", ana()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dueDate\":\"2026-12-01\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dueDate").value("2026-12-01"));
        mockMvc.perform(put("/api/action-plans/tasks/" + taskId).header("Authorization", ana()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clearDueDate\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dueDate").doesNotExist());
        mockMvc.perform(get("/api/action-plans/" + plan + "/tasks").header("Authorization", ana()))
                .andExpect(jsonPath("$[0].sourceLabel").value("Comunicação"));
    }

    @Test
    void invalidDueDateIs400() throws Exception {
        String plan = idOf(send("/api/action-plans", ana(), planJson("SQ1", "RETRO", "r1")));
        for (String bad : List.of("05/11/2026", "2026-02-30", "amanhã")) {
            assertEquals(400, send("/api/action-plans/" + plan + "/tasks", ana(), "{\"what\":\"x\",\"dueDate\":\"" + bad + "\"}").getResponse().getStatus(), bad);
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM action_plan_tasks", Integer.class));
    }

    @Test
    void oldTaskBodyWorksUnchanged() throws Exception {
        String plan = idOf(send("/api/action-plans", ana(), "{\"title\":\"P\",\"team\":\"SQ1\",\"squadId\":\"SQ1\"}"));
        mockMvc.perform(post("/api/action-plans/" + plan + "/tasks").header("Authorization", ana()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"what\":\"Algo\",\"why\":\"Porque\",\"where\":\"Aqui\",\"when\":\"hoje\",\"who\":\"Ana\",\"how\":\"Assim\",\"howMuch\":\"2h\",\"status\":\"todo\",\"order\":0}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.what").value("Algo"))
                .andExpect(jsonPath("$.when").value("hoje"))
                .andExpect(jsonPath("$.dueDate").doesNotExist())
                .andExpect(jsonPath("$.sourceItemId").doesNotExist());
    }
}
