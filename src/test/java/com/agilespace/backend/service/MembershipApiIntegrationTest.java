package com.agilespace.backend.service;

import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.MembershipDto;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Etapa 1 do vínculo contra um Postgres real: contrato de GET /api/me/memberships, shadow-diff somente leitura e a
 * evicção do cache pelo listener JPA (claim, troca de papel e gravação direta). Sem Docker o teste é ignorado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class MembershipApiIntegrationTest {

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
    @Autowired private ProjectMemberRoleRepository pmrRepository;
    @Autowired private SquadMemberRepository squadMemberRepository;
    @Autowired private MembershipService membershipService;
    @Autowired private MembershipCache cache;
    @Autowired private OnboardingService onboardingService;
    @Autowired private SquadTeamService squadTeamService;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE project_member_roles, squad_members, squad_member_exclusions, projects, link_requests, users CASCADE");
        cache.evictAll(); // TRUNCATE passa por fora do JPA
    }

    // ---------- fixtures ----------

    private String token(String id, String role) {
        return "Bearer " + jwtTokenUtil.generateToken(id, id + "@totvs.com.br", "Pessoa " + id, role, null, null, null);
    }

    private User user(String id, String role) {
        return userRepository.save(User.builder().id(id).email(id + "@totvs.com.br").name("Pessoa " + id).role(role).active(true).build());
    }

    private void project(String id, String name, String tribe) {
        jdbc.update("INSERT INTO projects (id, name, tribe_name, segment_name) VALUES (?, ?, ?, ?)", id, name, tribe, "Seg " + tribe);
    }

    private void pmr(String id, String project, String key, String roleName, boolean lead, String email, String userId, String source) {
        jdbc.update("INSERT INTO project_member_roles (id, project_id, role_name, role_key, display_name, email, user_id, is_leadership, source) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", id, project, roleName, key, "Nome " + id, email, userId, lead, source);
    }

    private JsonNode json(String body) throws Exception {
        return mapper.readTree(body);
    }

    // ---------- GET /api/me/memberships ----------

    @Test
    void semTokenDa401() throws Exception {
        mockMvc.perform(get("/api/me/memberships")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/me/memberships").header("Authorization", "Bearer lixo")).andExpect(status().isUnauthorized());
    }

    @Test
    void cadaUmVeSoOsProprios_comOFormatoDocumentado() throws Exception {
        project("SQ1", "Squad Um", "Tribo A");
        project("SQ2", "Squad Dois", "Tribo A");
        project("SQ3", "Squad Tres", "Tribo B");
        user("ana", "MEMBER");
        user("bia", "MEMBER");
        pmr("r-ana-dev", "SQ1", "DEVELOPER", "Developer", false, "ana@totvs.com.br", null, "JIRA");
        pmr("r-ana-po", "SQ1", "PRODUCT_OWNER", "Product Owner", true, "ana@totvs.com.br", null, "MANUAL");
        pmr("r-ana-sq3", "SQ3", "QA", "QA", false, null, "ana", "CLAIM");
        pmr("r-bia", "SQ2", "SCRUM_MASTER", "Scrum Master", true, "bia@totvs.com.br", null, null);

        String body = mockMvc.perform(get("/api/me/memberships").header("Authorization", token("ana", "MEMBER")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Membership-Contract", "1"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.contractVersion").value("1"))
                .andExpect(jsonPath("$.legacyOnly").value(false))
                .andExpect(jsonPath("$.transversalLeader").value(false))
                .andReturn().getResponse().getContentAsString();

        JsonNode ana = json(body);
        assertEquals(2, ana.get("memberships").size(), "só SQ1 e SQ3: nada da Bia");
        JsonNode sq1 = ana.get("memberships").get(0);
        assertEquals("SQ1", sq1.get("projectId").asText());
        assertEquals("Squad Um", sq1.get("projectName").asText());
        assertEquals("Tribo A", sq1.get("tribeName").asText());
        assertEquals("PRODUCT_OWNER", sq1.get("roleKey").asText());
        assertTrue(sq1.get("leadership").asBoolean());
        assertTrue(sq1.get("direct").asBoolean());
        assertEquals("SQUAD", sq1.get("scope").asText());
        assertEquals("MANUAL", sq1.get("source").asText());
        assertEquals(2, sq1.get("roles").size());
        assertTrue(sq1.get("roles").get(0).get("primary").asBoolean());
        assertEquals("DEVELOPER", sq1.get("roles").get(1).get("roleKey").asText());
        assertEquals("SQ1", ana.get("activeProjectId").asText(), "sem preferência: a primeira por precedência");
        assertEquals("SQ3", ana.get("memberships").get(1).get("projectId").asText());
        assertEquals("CLAIM", ana.get("memberships").get(1).get("source").asText());

        mockMvc.perform(get("/api/me/memberships").header("Authorization", token("bia", "MEMBER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberships.length()").value(1))
                .andExpect(jsonPath("$.memberships[0].projectId").value("SQ2"))
                .andExpect(jsonPath("$.memberships[0].source").doesNotExist());
    }

    @Test
    void ignoraQualquerIdNaUrl_eContaSemVinculoVemVazia() throws Exception {
        user("carla", "MEMBER");
        mockMvc.perform(get("/api/me/memberships").param("userId", "ana").header("Authorization", token("carla", "MEMBER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberships.length()").value(0))
                .andExpect(jsonPath("$.activeProjectId").doesNotExist())
                .andExpect(jsonPath("$.legacyOnly").value(false));
    }

    @Test
    void legacyOnly_contaSoComSquadIdAntigo() throws Exception {
        User dani = user("dani", "MEMBER");
        dani.setDefaultProjectId("SQUAD_ANTIGA");
        userRepository.save(dani);

        mockMvc.perform(get("/api/me/memberships").header("Authorization", token("dani", "MEMBER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legacyOnly").value(true))
                .andExpect(jsonPath("$.memberships.length()").value(0));
    }

    // ---------- GET /api/admin/membership/shadow-diff ----------

    private Map<String, Long> counts() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String t : List.of("users", "project_member_roles", "squad_members", "projects", "audit_logs", "notifications")) {
            out.put(t, jdbc.queryForObject("SELECT COUNT(*) FROM " + t, Long.class));
        }
        out.put("pmr_hash", jdbc.queryForObject("SELECT COALESCE(SUM(hashtext(id || coalesce(user_id,'') || coalesce(source,'') || role_key)), 0) FROM project_member_roles", Long.class));
        out.put("users_hash", jdbc.queryForObject("SELECT COALESCE(SUM(hashtext(id || coalesce(squad_id,'') || coalesce(default_project_id,''))), 0) FROM users", Long.class));
        return out;
    }

    @Test
    void shadowDiff_401_403_200_eNaoEscreveNada() throws Exception {
        project("SQ1", "Squad Um", "Tribo A");
        user("admin", "ADMIN");
        User ana = user("ana", "MEMBER");
        ana.setDefaultProjectId("SO_ROSTER"); // preferência fora dos vínculos: divergência esperada
        userRepository.save(ana);
        pmr("r-ana", "SQ1", "DEVELOPER", "Developer", false, "ana@totvs.com.br", null, "JIRA");
        user("bia", "MEMBER");

        mockMvc.perform(get("/api/admin/membership/shadow-diff")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/membership/shadow-diff").header("Authorization", token("bia", "MEMBER"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/membership/shadow-diff").header("Authorization", token("ana", "LEAD"))).andExpect(status().isForbidden());
        Map<String, Long> before = counts();

        String body = mockMvc.perform(get("/api/admin/membership/shadow-diff").header("Authorization", token("admin", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.sampleLimit").value(20))
                .andExpect(jsonPath("$.usersChecked").value(3))
                .andExpect(jsonPath("$.usersWithDivergence").value(1))
                .andExpect(jsonPath("$.totals.ACTIVE_PROJECT_DIFFERENT").value(1))
                .andExpect(jsonPath("$.totals.ONLY_LEGACY").value(0))
                .andExpect(jsonPath("$.totals.ONLY_NEW").value(0))
                .andExpect(jsonPath("$.totals.ROLE_DIFFERENT").value(0))
                .andExpect(jsonPath("$.sample.length()").value(1))
                .andExpect(jsonPath("$.sample[0].userId").value("ana"))
                .andExpect(jsonPath("$.sample[0].squad").value("SQ1"))
                .andExpect(jsonPath("$.sample[0].type").value("ACTIVE_PROJECT_DIFFERENT"))
                .andExpect(jsonPath("$.sample[0].detail").value("preferencia_fora_dos_vinculos"))
                .andExpect(jsonPath("$.truncated").value(false))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("@totvs.com.br"), "o relatório não leva e-mail");
        // O único registro novo é o do SecurityAuditInterceptor (toda chamada a /api/admin/** é auditada, igual ao relatório);
        // o serviço do shadow-diff não grava nada.
        Map<String, Long> after = counts();
        assertEquals(before.get("audit_logs") + 1, after.get("audit_logs"));
        before.remove("audit_logs");
        after.remove("audit_logs");
        assertEquals(before, after, "shadow-diff não grava nada");
        assertEquals(0, cache.size(), "e não popula o cache");
    }

    // ---------- evicção do cache ----------

    @Test
    void gravacaoDiretaDePmrInvalidaOCache() {
        project("SQ1", "Squad Um", "Tribo A");
        User ana = user("ana", "MEMBER");
        assertTrue(membershipService.forUser(ana).memberships().isEmpty());
        assertTrue(membershipService.forUser(ana).memberships().isEmpty());
        assertEquals(1, cache.size());

        pmrRepository.save(com.agilespace.backend.domain.ProjectMemberRole.builder().projectId("SQ1").roleName("Developer")
                .roleKey("DEVELOPER").displayName("Ana").email("ana@totvs.com.br").isLeadership(false).build());

        assertEquals(0, cache.size(), "o listener JPA limpou o cache na gravação");
        assertEquals(1, membershipService.forUser(ana).memberships().size());
    }

    @Test
    void claimInvalidaOCache() {
        project("SQ1", "Squad Um", "Tribo A");
        User ana = user("ana", "MEMBER");
        pmr("r-ana", "SQ1", "DEVELOPER", "Developer", false, "ana@totvs.com.br", null, null);
        long hits0 = cache.hits();
        MembershipDto antes = membershipService.forUser(ana).memberships().get(0);
        membershipService.forUser(ana); // segunda leitura vem do cache
        long hits = cache.hits();
        assertNull(antes.source());
        assertEquals(hits0 + 1, hits);

        onboardingService.claim("r-ana", ana);

        MembershipDto depois = membershipService.forUser(ana).memberships().get(0);
        assertEquals("CLAIM", depois.source(), "sem a evicção o cache ainda mostraria a origem antiga");
        assertEquals(hits, cache.hits(), "a leitura depois do claim foi um miss, não um hit");
    }

    @Test
    void changeRoleInvalidaOCache() {
        project("SQ1", "Squad Um", "Tribo A");
        User admin = user("admin", "ADMIN");
        User ana = user("ana", "MEMBER");
        pmr("r-ana", "SQ1", "DEVELOPER", "Developer", false, "ana@totvs.com.br", "ana", "JIRA");
        squadMemberRepository.save(SquadMember.builder().dbId("SQ1_acc-ana").squadId("SQ1").jiraAccountId("acc-ana")
                .displayName("Pessoa ana").email("ana@totvs.com.br").role("Developer").claimedByUid("ana").build());
        long hits = cache.hits();
        assertEquals("DEVELOPER", membershipService.forUser(ana).memberships().get(0).roleKey());
        membershipService.forUser(ana);
        assertEquals(hits + 1, cache.hits());

        squadTeamService.changeRole("SQ1", admin, "acc-ana", "Agile Master");

        MembershipDto depois = membershipService.forUser(ana).memberships().get(0);
        assertEquals("AGILE_MASTER", depois.roleKey());
        assertTrue(depois.leadership());
        assertEquals("MANUAL", depois.source());
        assertEquals(hits + 1, cache.hits(), "a leitura depois da troca de papel foi um miss");
    }
}
