package com.agilespace.backend.service;

import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.SquadMemberRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtTokenUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Etapa 2 do vínculo contra um Postgres real, com a chave no PADRÃO ({@code app.membership.authz=legacy}):
 * GET /api/admin/membership/authz-diff (401/403/200, somente leitura), a categoria WOULD_LOSE_ACCESS do relatório e a
 * garantia de que o padrão não muda quem lê o quê. Sem Docker o teste é ignorado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AuthzDiffIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenUtil jwtTokenUtil;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository userRepository;
    @Autowired private SquadMemberRepository squadMemberRepository;
    @Autowired private MembershipCache cache;
    @Autowired private AuthzShadow authzShadow;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE project_member_roles, squad_members, squad_member_exclusions, projects, link_requests, users CASCADE");
        cache.evictAll();
    }

    // ---------- fixtures ----------

    private String token(String id, String role) {
        return "Bearer " + jwtTokenUtil.generateToken(id, id + "@totvs.com.br", "Pessoa " + id, role, null, null, null);
    }

    private User user(String id, String role, String squadId, String jobTitle) {
        return userRepository.save(User.builder().id(id).email(id + "@totvs.com.br").name("Pessoa " + id).role(role).active(true)
                .defaultProjectId(squadId).jobTitle(jobTitle).build());
    }

    private void project(String id, String name, String tribe) {
        jdbc.update("INSERT INTO projects (id, name, tribe_name, segment_name) VALUES (?, ?, ?, ?)", id, name, tribe, "Seg " + tribe);
    }

    private void pmr(String id, String project, String key, String roleName, boolean lead, String userId) {
        jdbc.update("INSERT INTO project_member_roles (id, project_id, role_name, role_key, display_name, email, user_id, is_leadership, source) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL')", id, project, roleName, key, "Nome " + id, userId + "@totvs.com.br", userId, lead);
    }

    private void roster(String squad, String userId) {
        squadMemberRepository.save(SquadMember.builder().dbId(squad + "_" + userId).squadId(squad).jiraAccountId("jira-" + userId)
                .displayName("Pessoa " + userId).email(userId + "@totvs.com.br").role("Developer").build());
    }

    /** SQ1 com liderança (am), um dev por vínculo e quatro pessoas que só existem pelo modelo antigo; SQ2 sem liderança. */
    private void scenario() {
        project("SQ1", "Alfa", "Tribo A");
        project("SQ2", "Beta (sem liderança)", "Tribo B");
        user("admin", "ADMIN", null, null);
        user("am", "MEMBER", "SQ1", null);
        pmr("r-am", "SQ1", "AGILE_MASTER", "Agile Master", true, "am");
        user("dev", "MEMBER", "SQ1", null);
        pmr("r-dev", "SQ1", "DEVELOPER", "Developer", false, "dev");
        user("roster", "MEMBER", null, null);          // responsável de issue: só no roster
        roster("SQ1", "roster");
        user("solto", "MEMBER", "SQ1", null);          // users.squad_id solto
        user("cargo", "MEMBER", "SQ1", "Tech Lead");   // cargo + users.squad_id
        user("dev2", "MEMBER", "SQ2", null);           // vínculo em squad sem liderança (bootstrap): mantém
        pmr("r-dev2", "SQ2", "DEVELOPER", "Developer", false, "dev2");
        user("roster2", "MEMBER", null, null);         // roster em squad sem liderança: perde a gestão
        roster("SQ2", "roster2");
    }

    private Map<String, Long> counts() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String t : List.of("users", "project_member_roles", "squad_members", "projects", "audit_logs", "notifications")) {
            out.put(t, jdbc.queryForObject("SELECT COUNT(*) FROM " + t, Long.class));
        }
        out.put("pmr_hash", jdbc.queryForObject("SELECT COALESCE(SUM(hashtext(id || coalesce(user_id,'') || coalesce(source,'') || role_key)), 0) FROM project_member_roles", Long.class));
        out.put("users_hash", jdbc.queryForObject("SELECT COALESCE(SUM(hashtext(id || coalesce(default_project_id,''))), 0) FROM users", Long.class));
        return out;
    }

    // ---------- authz-diff ----------

    @Test
    void authzDiff_401_403_200_eNaoEscreveNada() throws Exception {
        scenario();

        mockMvc.perform(get("/api/admin/membership/authz-diff")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/membership/authz-diff").header("Authorization", token("dev", "MEMBER"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/membership/authz-diff").header("Authorization", token("am", "LEAD"))).andExpect(status().isForbidden());
        Map<String, Long> before = counts();

        String body = mockMvc.perform(get("/api/admin/membership/authz-diff").header("Authorization", token("admin", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.mode").value("legacy"))
                .andExpect(jsonPath("$.sampleLimit").value(20))
                .andExpect(jsonPath("$.usersChecked").value(7))
                .andExpect(jsonPath("$.usersSkippedPrivileged").value(1))
                .andExpect(jsonPath("$.usersFailed").value(0))
                .andExpect(jsonPath("$.usersWithDivergence").value(4))
                // roster, solto e cargo perdem a leitura de SQ1; roster2 perde a leitura de SQ2
                .andExpect(jsonPath("$.totals.LOSS").value(7))
                .andExpect(jsonPath("$.totals.GAIN").value(0))
                .andExpect(jsonPath("$.byCheck.matchesSquad.LOSS").value(4))
                .andExpect(jsonPath("$.byCheck.canManageSquad.LOSS").value(2))
                .andExpect(jsonPath("$.byCheck.isSquadLeadership.LOSS").value(1))
                .andExpect(jsonPath("$.byReason['LOSS:ONLY_ROSTER']").value(2))
                .andExpect(jsonPath("$.byReason['LOSS:ONLY_SQUAD_ID']").value(2))
                .andExpect(jsonPath("$.byReason['LOSS:ONLY_JOB_TITLE']").value(2))
                .andExpect(jsonPath("$.byReason['LOSS:NO_LEADERSHIP_FALLBACK']").value(1))
                .andExpect(jsonPath("$.sample.length()").value(7))
                .andExpect(jsonPath("$.truncated").value(false))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("@totvs.com.br"), "sem e-mail no authz-diff");
        JsonNode first = mapper.readTree(body).get("sample").get(0);
        assertTrue(first.has("userId") && first.has("squad") && first.has("check") && first.has("reason"));

        // O único registro novo é o do SecurityAuditInterceptor (toda chamada a /api/admin/** é auditada).
        Map<String, Long> after = counts();
        assertEquals(before.get("audit_logs") + 1, after.get("audit_logs"));
        before.remove("audit_logs");
        after.remove("audit_logs");
        assertEquals(before, after, "authz-diff não grava nada");
        assertEquals(0, cache.size(), "e não popula o cache de vínculos");
    }

    @Test
    void authzDiff_bancoSemDivergencia() throws Exception {
        project("SQ1", "Alfa", "Tribo A");
        user("admin", "ADMIN", null, null);
        user("am", "MEMBER", "SQ1", null);
        pmr("r-am", "SQ1", "AGILE_MASTER", "Agile Master", true, "am");

        mockMvc.perform(get("/api/admin/membership/authz-diff").header("Authorization", token("admin", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.LOSS").value(0))
                .andExpect(jsonPath("$.totals.GAIN").value(0))
                .andExpect(jsonPath("$.sample.length()").value(0))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    // ---------- WOULD_LOSE_ACCESS no relatório de exceções ----------

    @Test
    void relatorioTemWouldLoseAccess_comMotivoCategorico() throws Exception {
        scenario();

        String body = mockMvc.perform(get("/api/admin/membership/report").header("Authorization", token("admin", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[?(@.code=='WOULD_LOSE_ACCESS')].count").value(7))
                .andReturn().getResponse().getContentAsString();

        JsonNode category = null;
        for (JsonNode c : mapper.readTree(body).get("categories")) {
            if ("WOULD_LOSE_ACCESS".equals(c.get("code").asText())) category = c;
        }
        assertNotNull(category);
        assertEquals(7, category.get("count").asLong());
        assertEquals(2, category.get("breakdown").get("matchesSquad:ONLY_ROSTER").asLong());
        assertEquals(2, category.get("breakdown").get("matchesSquad:ONLY_SQUAD_ID").asLong());
        assertEquals(1, category.get("breakdown").get("canManageSquad:ONLY_JOB_TITLE").asLong());
        assertEquals(1, category.get("breakdown").get("canManageSquad:NO_LEADERSHIP_FALLBACK").asLong());
        assertEquals(1, category.get("breakdown").get("isSquadLeadership:ONLY_JOB_TITLE").asLong());
        assertEquals(7, category.get("sample").size());
        assertFalse(category.get("truncated").asBoolean());
        JsonNode row = category.get("sample").get(0);
        assertTrue(row.get("id").asText().length() > 0);
        assertTrue(row.get("detail").asText().contains(":"), "ponto:motivo");

        mockMvc.perform(get("/api/admin/membership/report.csv").header("Authorization", token("admin", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("CATEGORIA;WOULD_LOSE_ACCESS;")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AMOSTRA;WOULD_LOSE_ACCESS;")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("matchesSquad:ONLY_ROSTER")));
    }

    // ---------- o padrão não muda o comportamento ----------

    @Test
    void padraoELegacy_eRosterOnlyContinuaLendoComoHoje() throws Exception {
        scenario();
        assertEquals(AuthzMode.LEGACY, authzShadow.mode(), "sem APP_MEMBERSHIP_AUTHZ o padrão é legacy");

        mockMvc.perform(get("/api/squads/SQ1/members").header("Authorization", token("roster", "MEMBER"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/squads/SQ1/members").header("Authorization", token("solto", "MEMBER"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/squads/SQ1/members").header("Authorization", token("dev", "MEMBER"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/squads/SQ2/members").header("Authorization", token("dev", "MEMBER"))).andExpect(status().isForbidden());
    }
}
