package com.agilespace.backend.service;

import com.agilespace.backend.security.JwtTokenUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Etapa 4 do vínculo contra um Postgres real, com {@code app.membership.lead-level=off}: a migração V77 (aditiva e
 * idempotente: rodada duas vezes), o rebaixamento de LEAD ponta a ponta (dry-run, apply, forceDemote, restore, auditoria,
 * sessão viva), o relatório e a chave derivada LEADERSHIP. Sem Docker o teste é ignorado, como os demais Testcontainers.
 */
@SpringBootTest(properties = "app.membership.lead-level=off")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class LeadDemotionIntegrationTest {

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
    @Autowired private MembershipReportService reportService;
    @Autowired private SquadAudienceService audienceService;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE project_member_roles, squad_members, squad_member_exclusions, projects, link_requests, users, "
                + "users_role_lead_backup, global_announcements, audit_logs, api_keys CASCADE");
    }

    // ---------- fixture ----------

    private void project(String id, String tribe) {
        jdbc.update("INSERT INTO projects (id, name, tribe_name, segment_name) VALUES (?, ?, ?, ?)", id, "Squad " + id, tribe, "Seg " + id);
    }

    private void user(String id, String role, boolean active) {
        jdbc.update("INSERT INTO users (id, email, name, role, is_active, is_guest, password_hash) VALUES (?, ?, ?, ?, ?, FALSE, 'hash')",
                id, id + "@totvs.com.br", "Pessoa " + id, role, active);
    }

    private void pmr(String id, String project, String roleKey, boolean lead, String userId) {
        jdbc.update("INSERT INTO project_member_roles (id, project_id, role_name, role_key, display_name, email, user_id, is_leadership, source) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL')", id, project, roleKey, roleKey, "Nome " + id, userId + "@totvs.com.br", userId, lead);
    }

    private void announcement(String id, String... roles) {
        jdbc.update("INSERT INTO global_announcements (id, title, content, created_by, created_at, type, audience_type, valid_from) "
                + "VALUES (?, 'T', 'C', 'adm@x.com', NOW(), 'INFO', 'ROLES', NOW())", id);
        for (String r : roles) {
            jdbc.update("INSERT INTO global_announcement_roles (announcement_id, role) VALUES (?, ?)", id, r);
        }
    }

    private String role(String userId) {
        return jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, userId);
    }

    private String token(String id, String role) {
        return "Bearer " + jwtTokenUtil.generateToken(id, id + "@totvs.com.br", "Pessoa " + id, role, null, null, null);
    }

    private void runV77() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(new ClassPathResource("db/migration/V77__lead_level_backup.sql"), StandardCharsets.UTF_8));
        }
    }

    private Map<String, String> backup() {
        Map<String, String> out = new java.util.TreeMap<>();
        jdbc.query("SELECT id, role FROM users_role_lead_backup", rs -> { out.put(rs.getString("id"), rs.getString("role")); });
        return out;
    }

    private Set<String> announcementRoles(String id) {
        return Set.copyOf(jdbc.queryForList("SELECT role FROM global_announcement_roles WHERE announcement_id = ?", String.class, id));
    }

    /** ana (LEAD com liderança em SQ1), bia (LEAD sem vínculo), caio (LEAD inativo com liderança), maria (MEMBER), admin. */
    private void leadFixture() {
        project("SQ1", "T1");
        project("SQ2", "T2");
        user("admin", "ADMIN", true);
        user("ana", "LEAD", true);
        user("bia", "lead", true);
        user("caio", "LEAD", false);
        user("maria", "MEMBER", true);
        pmr("p-ana", "SQ1", "AGILE_MASTER", true, "ana");
        pmr("p-caio", "SQ2", "PEOPLE_LEAD", true, "caio");
        pmr("p-maria", "SQ1", "DEVELOPER", false, "maria");
    }

    // ---------- V77 ----------

    @Test
    void v77EAditivaEIdempotenteRodandoDuasVezes() throws Exception {
        leadFixture();
        announcement("av-lead", "LEAD", "ADMIN");
        announcement("av-ambos", "LEAD", "LEADERSHIP");
        announcement("av-outro", "AGILE_MASTER");
        long pmrBefore = jdbc.queryForObject("SELECT COUNT(*) FROM project_member_roles", Long.class);

        runV77();

        assertEquals(Map.of("ana", "LEAD", "bia", "lead", "caio", "LEAD"), backup(), "só os LEAD (sem caixa), com o valor original");
        assertEquals(Set.of("LEADERSHIP", "ADMIN"), announcementRoles("av-lead"));
        assertEquals(Set.of("LEADERSHIP"), announcementRoles("av-ambos"), "já tinha LEADERSHIP: o LEAD repetido só sai");
        assertEquals(Set.of("AGILE_MASTER"), announcementRoles("av-outro"));
        // aditiva: não rebaixa, não cria vínculo
        assertEquals("LEAD", role("ana"));
        assertEquals("lead", role("bia"));
        assertEquals(pmrBefore, jdbc.queryForObject("SELECT COUNT(*) FROM project_member_roles", Long.class));

        // entre as duas rodadas: um LEAD novo, um rebaixado por fora e o backup antigo editado
        user("dora", "LEAD", true);
        jdbc.update("UPDATE users SET role = 'MEMBER' WHERE id = 'ana'");
        jdbc.update("UPDATE users_role_lead_backup SET migrated_at = TIMESTAMP '2020-01-01 00:00:00' WHERE id = 'ana'");
        announcement("av-novo", "LEAD");

        runV77();

        Map<String, String> after = backup();
        assertEquals(Map.of("ana", "LEAD", "bia", "lead", "caio", "LEAD", "dora", "LEAD"), after, "dora entra; ninguém duplica nem sai");
        assertEquals(2020, jdbc.queryForObject("SELECT EXTRACT(YEAR FROM migrated_at)::int FROM users_role_lead_backup WHERE id = 'ana'", Integer.class),
                "reexecutar não sobrescreve o backup existente");
        assertEquals("MEMBER", role("ana"), "a migração nunca mexe em users.role");
        assertEquals(Set.of("LEADERSHIP"), announcementRoles("av-novo"));
        assertEquals(Set.of("LEADERSHIP", "ADMIN"), announcementRoles("av-lead"));
        assertEquals(Set.of("LEADERSHIP"), announcementRoles("av-ambos"));
    }

    // ---------- endpoints ----------

    @Test
    void soAdminChamaAsFerramentas() throws Exception {
        leadFixture();
        mockMvc.perform(post("/api/admin/membership/lead-demotion")).andExpect(status().is4xxClientError());
        mockMvc.perform(post("/api/admin/membership/lead-demotion").header("Authorization", token("ana", "LEAD"))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/membership/lead-demotion").header("Authorization", token("maria", "MEMBER"))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/membership/lead-restore").header("Authorization", token("maria", "MEMBER"))).andExpect(status().isForbidden());
        assertEquals("LEAD", role("ana"));
    }

    @Test
    void dryRunNaoGravaENomeiaAClassificacao() throws Exception {
        leadFixture();

        mockMvc.perform(post("/api/admin/membership/lead-demotion").header("Authorization", token("admin", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("DRY_RUN"))
                .andExpect(jsonPath("$.leadLevel").value("off"))
                .andExpect(jsonPath("$.limit").value(20))
                .andExpect(jsonPath("$.summary.leadUsers").value(3))
                .andExpect(jsonPath("$.summary.hasLeadershipLink").value(2))
                .andExpect(jsonPath("$.summary.noLeadershipLink").value(1))
                .andExpect(jsonPath("$.summary.wouldDemote").value(2))
                .andExpect(jsonPath("$.summary.demoted").value(0))
                .andExpect(jsonPath("$.users[?(@.userId=='ana')].classification").value("HAS_LEADERSHIP_LINK"))
                .andExpect(jsonPath("$.users[?(@.userId=='ana')].leadershipSquads[0]").value("SQ1"))
                .andExpect(jsonPath("$.users[?(@.userId=='ana')].action").value("WOULD_DEMOTE"))
                .andExpect(jsonPath("$.users[?(@.userId=='bia')].classification").value("NO_LEADERSHIP_LINK"))
                .andExpect(jsonPath("$.users[?(@.userId=='bia')].action").value("KEPT_NO_LEADERSHIP_LINK"))
                .andExpect(jsonPath("$.users[?(@.userId=='caio')].active").value(false));

        assertEquals("LEAD", role("ana"));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM users_role_lead_backup", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action IN ('LEAD_DEMOTED', 'LEAD_DEMOTED_FORCED', 'LEAD_RESTORED')", Long.class),
                "o dry-run só deixa o registro genérico de acesso a /api/admin, nenhuma ação LEAD_*");
    }

    @Test
    void applyRebaixaSoQuemTemVinculoEATokenVivoValeComoMember() throws Exception {
        leadFixture();
        // com off o LEAD não lê squad alheia nem com o token antigo; ana (vínculo em SQ1) lê SQ1
        mockMvc.perform(get("/api/squads/SQ1/members").header("Authorization", token("bia", "LEAD"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/squads/SQ2/members").header("Authorization", token("ana", "LEAD"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/squads/SQ1/members").header("Authorization", token("ana", "LEAD"))).andExpect(status().isOk());

        jdbc.update("INSERT INTO api_keys (id, name, key_hash, owner_user_id, owner_role, created_at) VALUES (gen_random_uuid(), 'k', repeat('a', 64), 'ana', 'LEAD', NOW())");

        mockMvc.perform(post("/api/admin/membership/lead-demotion").param("apply", "true").header("Authorization", token("admin", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("APPLIED"))
                .andExpect(jsonPath("$.summary.demoted").value(2))
                .andExpect(jsonPath("$.users[?(@.userId=='ana')].action").value("DEMOTED"))
                .andExpect(jsonPath("$.users[?(@.userId=='ana')].activeApiKeys").value(1))
                .andExpect(jsonPath("$.users[?(@.userId=='ana')].apiKeysNote").exists())
                .andExpect(jsonPath("$.users[?(@.userId=='caio')].action").value("DEMOTED"))
                .andExpect(jsonPath("$.users[?(@.userId=='bia')].action").value("KEPT_NO_LEADERSHIP_LINK"));

        assertEquals("MEMBER", role("ana"));
        assertEquals("MEMBER", role("caio"));
        assertEquals("lead", role("bia"), "sem liderança por vínculo: não rebaixa");
        assertEquals(Map.of("ana", "LEAD", "caio", "LEAD"), backup());
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action = 'LEAD_DEMOTED'", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM api_keys WHERE owner_user_id = 'ana' AND revoked_at IS NULL AND owner_role = 'LEAD'", Long.class),
                "as chaves de API não são alteradas");
        // a sessão viva já é de MEMBER (cache derrubado): o vínculo de ana continua valendo
        mockMvc.perform(get("/api/squads/SQ1/members").header("Authorization", token("ana", "LEAD"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/membership/report").header("Authorization", token("ana", "LEAD"))).andExpect(status().isForbidden());
    }

    @Test
    void loteELimite() throws Exception {
        leadFixture();

        mockMvc.perform(post("/api/admin/membership/lead-demotion").param("apply", "true").param("limit", "1")
                        .header("Authorization", token("admin", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.demoted").value(1))
                .andExpect(jsonPath("$.summary.overLimit").value(1));

        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE UPPER(role) = 'LEAD' AND id IN ('ana','caio')", Long.class));
        mockMvc.perform(post("/api/admin/membership/lead-demotion").param("limit", "0").header("Authorization", token("admin", "ADMIN")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/membership/lead-demotion").param("forceDemote", "true").header("Authorization", token("admin", "ADMIN")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/membership/lead-demotion").param("userId", "nao-existe").header("Authorization", token("admin", "ADMIN")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/admin/membership/lead-demotion").param("userId", "maria").header("Authorization", token("admin", "ADMIN")))
                .andExpect(status().isConflict());
    }

    @Test
    void forceDemoteEOrestauraSaoAuditadosEReversiveis() throws Exception {
        leadFixture();

        mockMvc.perform(post("/api/admin/membership/lead-demotion").param("apply", "true").param("userId", "bia")
                        .header("Authorization", token("admin", "ADMIN")))
                .andExpect(jsonPath("$.users[0].action").value("KEPT_NO_LEADERSHIP_LINK"));
        assertEquals("lead", role("bia"));

        mockMvc.perform(post("/api/admin/membership/lead-demotion").param("apply", "true").param("userId", "bia").param("forceDemote", "true")
                        .header("Authorization", token("admin", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users[0].action").value("DEMOTED_FORCED"));
        assertEquals("MEMBER", role("bia"));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action = 'LEAD_DEMOTED_FORCED'", Long.class));

        mockMvc.perform(post("/api/admin/membership/lead-demotion").param("apply", "true").header("Authorization", token("admin", "ADMIN")))
                .andExpect(jsonPath("$.summary.demoted").value(2));
        assertEquals(Map.of("ana", "LEAD", "bia", "lead", "caio", "LEAD"), backup());

        // restaura um
        mockMvc.perform(post("/api/admin/membership/lead-restore").param("userId", "ana").header("Authorization", token("admin", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restored").value(1))
                .andExpect(jsonPath("$.users[0].result").value("RESTORED"));
        assertEquals("LEAD", role("ana"));
        assertEquals("MEMBER", role("caio"));

        // um ADMIN promovido depois nunca é sobrescrito
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = 'caio'");
        // restaura todos
        mockMvc.perform(post("/api/admin/membership/lead-restore").header("Authorization", token("admin", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restored").value(1))
                .andExpect(jsonPath("$.skipped").value(2))
                .andExpect(jsonPath("$.users[?(@.userId=='caio')].reason").value("CURRENT_ROLE_NOT_MEMBER"))
                .andExpect(jsonPath("$.users[?(@.userId=='ana')].reason").value("ALREADY_LEAD"));
        assertEquals("LEAD", role("bia"));
        assertEquals("ADMIN", role("caio"));
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action = 'LEAD_RESTORED'", Long.class));
        mockMvc.perform(post("/api/admin/membership/lead-restore").param("userId", "maria").header("Authorization", token("admin", "ADMIN")))
                .andExpect(jsonPath("$.users[0].reason").value("NOT_IN_BACKUP"));
    }

    // ---------- relatório e chave derivada ----------

    @Test
    void relatorioTemLeadNoLeadershipLink() {
        leadFixture();

        MembershipReportDtoView report = new MembershipReportDtoView(reportService.generate());

        assertEquals(2, report.count("LEAD_USERS"), "ana e bia (caio está inativo)");
        assertEquals(1, report.count("LEAD_NO_LEADERSHIP_LINK"));
        assertEquals("bia", report.firstId("LEAD_NO_LEADERSHIP_LINK"));
        assertEquals("sem_lideranca_por_vinculo", report.firstDetail("LEAD_NO_LEADERSHIP_LINK"));
    }

    @Test
    void chaveDerivadaLeadershipEOsRotulosLead() {
        leadFixture();

        Set<String> leadership = audienceService.userIdsWithRoles(List.of("LEADERSHIP"));
        assertEquals(Set.of("ana"), leadership, "pmr is_leadership + conta ativa (caio está inativo); bia (LEAD sem vínculo) fora");
        assertEquals(Set.of(), audienceService.userIdsWithRoles(List.of("LEAD")), "off: LEAD não casa ninguém");
        assertEquals(Set.of("admin"), audienceService.userIdsWithRoles(List.of("ADMIN")));
    }

    /** Leitura enxuta do relatório. */
    private record MembershipReportDtoView(com.agilespace.backend.dto.MembershipReportDto dto) {
        long count(String code) {
            return dto.categories().stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow().count();
        }

        String firstId(String code) {
            return dto.categories().stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow().sample().get(0).id();
        }

        String firstDetail(String code) {
            return dto.categories().stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow().sample().get(0).detail();
        }
    }
}
