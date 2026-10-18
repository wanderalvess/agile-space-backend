package com.agilespace.backend.service;

import com.agilespace.backend.domain.ProjectConfig;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.dto.MembershipReportDto;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fundação do vínculo (V75) contra um Postgres real: a migração idempotente (rodada duas vezes dá o mesmo resultado, com
 * o backfill conservador), a reimportação do time com ids estáveis e liderança preservada, o relatório com uma fixture por
 * inconsistência e a autorização do endpoint. Sem Docker o teste é ignorado, como os demais Testcontainers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class MembershipFoundationIntegrationTest {

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
    @Autowired private JiraProfieldsService jiraProfieldsService;
    @Autowired private ProjectMemberRoleRepository pmrRepository;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE project_member_roles, squad_members, squad_member_exclusions, projects, link_requests, users CASCADE");
    }

    // ---------- helpers de fixture (SQL cru: controla source/user_id/colunas que a entidade não deixa nulas) ----------

    private void project(String id, String tribe) {
        jdbc.update("INSERT INTO projects (id, name, tribe_name, segment_name) VALUES (?, ?, ?, ?)", id, "Squad " + id, tribe, "Seg " + id);
    }

    private void user(String id, String email, String role, boolean active, String squadId, String defaultProject, String jira, boolean password) {
        // users.squad_id foi apagada (V82): o parâmetro squadId das fixtures vira a squad ativa preferida (default_project_id)
        // quando esta vier vazia.
        String preferred = defaultProject != null ? defaultProject : squadId;
        jdbc.update("INSERT INTO users (id, email, name, role, is_active, is_guest, default_project_id, jira_account_id, password_hash) "
                + "VALUES (?, ?, ?, ?, ?, FALSE, ?, ?, ?)", id, email, "Pessoa " + id, role, active, preferred, jira, password ? "hash" : null);
    }

    private void pmr(String id, String project, String roleKey, boolean lead, String email, String jira, String userId, String source) {
        jdbc.update("INSERT INTO project_member_roles (id, project_id, role_name, role_key, display_name, email, jira_account_id, user_id, is_leadership, source) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, project, roleKey, roleKey, "Nome " + id, email, jira, userId, lead, source);
    }

    private void roster(String dbId, String squad, String jira, String email, String claimedBy) {
        jdbc.update("INSERT INTO squad_members (db_id, squad_id, jira_account_id, display_name, email, role, claimed_by_uid) VALUES (?, ?, ?, ?, ?, 'Developer', ?)",
                dbId, squad, jira, "Roster " + dbId, email, claimedBy);
    }

    private void runV75() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(new ClassPathResource("db/migration/V75__membership_foundation.sql"), StandardCharsets.UTF_8));
        }
    }

    private List<Map<String, Object>> pmrSnapshot() {
        return jdbc.queryForList("SELECT id, project_id, role_key, is_leadership, user_id, source, email, jira_account_id FROM project_member_roles ORDER BY id");
    }

    private String token(String id, String role) {
        return "Bearer " + jwtTokenUtil.generateToken(id, id + "@totvs.com.br", "Pessoa " + id, role, null, null, null);
    }

    // ---------- V75 ----------

    @Test
    void v75CriaIndicesEColunaEFicaIdempotenteRodandoDuasVezes() throws Exception {
        project("SQ1", "T1");
        project("SQ2", "T1");
        // casa por e-mail com UMA conta ativa -> backfill
        user("u-ana", "ana@x.com", "MEMBER", true, null, null, "acc-ana", true);
        pmr("p-ana", "SQ1", "DEVELOPER", false, "  ANA@x.com ", "acc-ana", null, null);
        // e-mail casa com DUAS contas (caixas diferentes) -> nunca toca
        user("u-dup1", "dup@x.com", "MEMBER", true, null, null, null, true);
        user("u-dup2", "DUP@x.com", "MEMBER", true, null, null, null, true);
        pmr("p-dup", "SQ1", "DEVELOPER", false, "dup@x.com", null, null, null);
        // conta inativa -> nunca toca
        user("u-off", "off@x.com", "MEMBER", false, null, null, null, true);
        pmr("p-off", "SQ1", "DEVELOPER", false, "off@x.com", null, null, null);
        // já tem user_id -> nunca toca (nem o source)
        user("u-bia", "bia@x.com", "MEMBER", true, null, null, null, true);
        user("u-outra", "outra@x.com", "MEMBER", true, null, null, null, true);
        pmr("p-bia", "SQ1", "DEVELOPER", false, "bia@x.com", null, "u-outra", null);
        // origem registrada -> nunca toca
        user("u-gus", "gus@x.com", "MEMBER", true, null, null, null, true);
        pmr("p-gus", "SQ1", "PRODUCT_OWNER", true, "gus@x.com", null, null, "MANUAL");
        // conta do Jira da linha pertence a OUTRA conta -> pula
        user("u-edu", "edu@x.com", "MEMBER", true, null, null, null, true);
        user("u-dono-jira", "dono@x.com", "MEMBER", true, null, null, "acc-do-dono", true);
        pmr("p-edu", "SQ1", "DEVELOPER", false, "edu@x.com", "acc-do-dono", null, null);
        // sem e-mail: não casa por jira_account_id (fora do escopo do backfill)
        pmr("p-jira-only", "SQ1", "DEVELOPER", false, null, "acc-do-dono", null, null);
        // roster reivindicado sem vínculo, projeto existe -> ganha pmr MIGRATION sem liderança
        user("u-caio", "caio@x.com", "MEMBER", true, null, null, "acc-caio", true);
        roster("SQ2_acc-caio", "SQ2", "acc-caio", "caio@x.com", "u-caio");
        // roster reivindicado de squad que NÃO existe em projects -> nada
        user("u-fer", "fer@x.com", "MEMBER", true, null, null, null, true);
        roster("SOROSTER_acc-fer", "SOROSTER", "acc-fer", "fer@x.com", "u-fer");
        // roster reivindicado que já tem vínculo (por e-mail) -> não duplica
        user("u-gi", "gi@x.com", "MEMBER", true, null, null, null, true);
        pmr("p-gi", "SQ2", "QA", false, "GI@x.com", null, null, null);
        roster("SQ2_acc-gi", "SQ2", "acc-gi", "gi@x.com", "u-gi");

        runV75();
        List<Map<String, Object>> first = pmrSnapshot();
        runV75();
        List<Map<String, Object>> second = pmrSnapshot();

        assertEquals(first, second, "rodar a V75 de novo não muda nada");

        Map<String, Map<String, Object>> byId = first.stream().collect(Collectors.toMap(r -> (String) r.get("id"), r -> r));
        assertEquals("u-ana", byId.get("p-ana").get("user_id"));
        assertEquals("BACKFILL", byId.get("p-ana").get("source"));
        assertNull(byId.get("p-dup").get("user_id"), "e-mail com mais de uma conta não é backfillado");
        assertNull(byId.get("p-dup").get("source"));
        assertNull(byId.get("p-off").get("user_id"), "conta inativa não é backfillada");
        assertEquals("u-outra", byId.get("p-bia").get("user_id"));
        assertNull(byId.get("p-bia").get("source"), "linha que já tinha user_id não é tocada");
        assertNull(byId.get("p-gus").get("user_id"));
        assertEquals("MANUAL", byId.get("p-gus").get("source"));
        assertNull(byId.get("p-edu").get("user_id"), "conta do Jira da linha é de outra conta: pula");
        assertNull(byId.get("p-jira-only").get("user_id"), "não casa por jira_account_id");
        assertEquals(true, byId.get("p-gus").get("is_leadership"), "liderança nunca é alterada");

        List<Map<String, Object>> migrated = first.stream().filter(r -> "MIGRATION".equals(r.get("source"))).toList();
        assertEquals(1, migrated.size(), "só o roster reivindicado, de squad existente e sem vínculo, vira pmr");
        assertEquals("SQ2", migrated.get(0).get("project_id"));
        assertEquals("u-caio", migrated.get(0).get("user_id"));
        assertEquals(false, migrated.get(0).get("is_leadership"));
        assertEquals(0, first.stream().filter(r -> "SOROSTER".equals(r.get("project_id"))).count());
        assertEquals(1, first.stream().filter(r -> "SQ2".equals(r.get("project_id")) && "QA".equals(r.get("role_key"))).count());

        Integer idx = jdbc.queryForObject("SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'project_member_roles' "
                + "AND indexname IN ('idx_pmr_user_id', 'idx_pmr_email_lower', 'idx_pmr_email_upper')", Integer.class);
        assertEquals(3, idx);
        Integer column = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_name = 'project_member_roles' AND column_name = 'source'", Integer.class);
        assertEquals(1, column);

        // Reversão documentada: soltar o user_id só das linhas BACKFILL volta ao estado anterior, sem apagar linha.
        int reverted = jdbc.update("UPDATE project_member_roles SET user_id = NULL, source = NULL WHERE source = 'BACKFILL'");
        assertEquals(2, reverted, "p-ana e p-gi (o e-mail de p-gi casa com u-gi, então não ganhou linha MIGRATION duplicada)");
        assertNull(jdbc.queryForObject("SELECT user_id FROM project_member_roles WHERE id = 'p-ana'", String.class));
    }

    // ---------- reimportação do time ----------

    @Test
    void reimportacaoGravaNoLugarComIdsEstaveisEPreservaLiderancaManual() {
        ProjectConfig project = ProjectConfig.builder().id("IMP").name("Squad IMP").build();
        jdbc.update("INSERT INTO projects (id, name) VALUES ('IMP', 'Squad IMP')");
        user("u-ana", "ana@x.com", "MEMBER", true, null, null, null, true);
        pmr("a-ana", "IMP", "DEVELOPER", false, "ana@x.com", "acc-ana", null, "JIRA");           // continua no Jira
        pmr("b-bia", "IMP", "PRODUCT_OWNER", true, "bia@x.com", "acc-bia", "u-ana", null);       // legado com conta, sumiu do Jira
        pmr("c-manual", "IMP", "AGILE_MASTER", true, "am@x.com", null, null, "MANUAL");          // liderança à mão, sem conta
        pmr("d-fim", "IMP", "DEVELOPER", false, "fim@x.com", "acc-fim", null, "JIRA");           // sumiu do Jira, sem conta
        pmr("e-legado", "IMP", "QA", false, "leg@x.com", "acc-leg", null, null);                 // legado sem conta, sumiu do Jira

        ProjectMemberRole jiraAna = ProjectMemberRole.builder().projectId("IMP").roleName("Product Owner").roleKey("PRODUCT_OWNER")
                .jiraAccountId("acc-ana").displayName("Ana do Jira").email("ana@x.com").isLeadership(true).build();
        ProjectMemberRole jiraNovo = ProjectMemberRole.builder().projectId("IMP").roleName("Developer").roleKey("DEVELOPER")
                .jiraAccountId("acc-novo").displayName("Novo").email("novo@x.com").isLeadership(false).build();

        jiraProfieldsService.persistImport(project, List.of(jiraAna, jiraNovo), List.of(jiraAna, jiraNovo));

        List<ProjectMemberRole> after = pmrRepository.findByProjectId("IMP");
        Map<String, ProjectMemberRole> byId = after.stream().collect(Collectors.toMap(ProjectMemberRole::getId, r -> r));
        assertTrue(byId.containsKey("a-ana"), "mesma linha, mesmo id");
        assertEquals("PRODUCT_OWNER", byId.get("a-ana").getRoleKey(), "o papel segue o Jira numa linha do roster");
        assertTrue(byId.get("a-ana").isLeadership());
        assertTrue(byId.containsKey("b-bia"), "linha com user_id é preservada");
        assertTrue(byId.containsKey("c-manual"), "liderança manual sobrevive");
        assertTrue(byId.get("c-manual").isLeadership());
        assertEquals("MANUAL", byId.get("c-manual").getSource());
        assertFalse(byId.containsKey("d-fim"), "linha do Jira sem conta que o Jira deixou de listar sai");
        assertFalse(byId.containsKey("e-legado"), "legado sem conta que o Jira deixou de listar sai");
        ProjectMemberRole created = after.stream().filter(r -> "acc-novo".equals(r.getJiraAccountId())).findFirst().orElseThrow();
        assertEquals("JIRA", created.getSource());
        assertEquals(4, after.size());

        // Segunda reimportação idêntica: nenhum id muda.
        List<String> idsBefore = after.stream().map(ProjectMemberRole::getId).sorted().toList();
        ProjectMemberRole jiraAna2 = ProjectMemberRole.builder().projectId("IMP").roleName("Product Owner").roleKey("PRODUCT_OWNER")
                .jiraAccountId("acc-ana").displayName("Ana do Jira").email("ana@x.com").isLeadership(true).build();
        ProjectMemberRole jiraNovo2 = ProjectMemberRole.builder().projectId("IMP").roleName("Developer").roleKey("DEVELOPER")
                .jiraAccountId("acc-novo").displayName("Novo").email("novo@x.com").isLeadership(false).build();
        jiraProfieldsService.persistImport(project, List.of(jiraAna2, jiraNovo2), List.of(jiraAna2, jiraNovo2));
        List<String> idsAfter = pmrRepository.findByProjectId("IMP").stream().map(ProjectMemberRole::getId).sorted().toList();
        assertEquals(idsBefore, idsAfter, "ids estáveis entre reimportações");
    }

    // ---------- relatório ----------

    private MembershipReportDto.Category cat(MembershipReportDto report, String code) {
        return report.categories().stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow(() -> new AssertionError("categoria ausente: " + code));
    }

    private boolean has(MembershipReportDto.Category c, String id) {
        return c.sample().stream().anyMatch(r -> id.equals(r.id()));
    }

    private void relatorioFixtures() {
        project("RP_OK", "T1");
        project("RP_NOLEAD", "T1");
        project("RP_NOTRIBE", null);
        project("RP_NOAPPR", "T9");

        // squad saudável: AM com conta
        user("rp-am", "am@x.com", "MEMBER", true, "RP_OK", "RP_OK", null, true);
        pmr("m-am", "RP_OK", "AGILE_MASTER", true, "am@x.com", null, "rp-am", null);
        // PO e Developer na mesma squad
        user("rp-twice", "twice@x.com", "MEMBER", true, "RP_OK", "RP_OK", null, true);
        pmr("m-twice-po", "RP_OK", "PRODUCT_OWNER", true, "twice@x.com", null, "rp-twice", null);
        pmr("m-twice-dev", "RP_OK", "DEVELOPER", false, "twice@x.com", null, "rp-twice", null);
        // pessoa em duas squads + default divergente do squad_id
        user("rp-multi", "multi@x.com", "MEMBER", true, "RP_OK", "RP_NOLEAD", null, true);
        pmr("m-multi-1", "RP_OK", "DEVELOPER", false, "multi@x.com", null, "rp-multi", null);
        pmr("m-multi-2", "RP_NOLEAD", "DEVELOPER", false, "multi@x.com", null, "rp-multi", null);
        // squad sem liderança (RP_NOLEAD só tem contribuidor) e sem tribo/pessoas (RP_NOTRIBE)
        // squad com liderança que não é aprovadora (Tech Lead)
        user("rp-tl", "tl@x.com", "MEMBER", true, "RP_NOAPPR", "RP_NOAPPR", null, true);
        pmr("m-tl", "RP_NOAPPR", "TECH_LEAD", true, "tl@x.com", null, "rp-tl", null);

        // usuários com squad e sem vínculo, cada rótulo
        user("rp-nolink", "nolink@x.com", "MEMBER", true, "RP_NOLEAD", null, null, true);
        user("rp-sentinel", "sentinel@x.com", "MEMBER", true, "Sem Time", null, null, true);
        user("rp-nonproject", "nonproject@x.com", "MEMBER", true, "Compras", null, null, true);
        user("rp-nonproject2", "nonproject2@x.com", "MEMBER", true, "COMPRAS", null, null, true);
        user("rp-rosteronly", "rosteronly@x.com", "MEMBER", true, "RP_OK", null, "acc-ro", true);
        roster("RP_OK_acc-ro", "RP_OK", "acc-ro", "rosteronly@x.com", "rp-rosteronly");

        // LEAD com e sem liderança por vínculo; liderança transversal
        user("rp-lead1", "lead1@x.com", "LEAD", true, null, null, null, true);
        pmr("m-lead1", "RP_OK", "PEOPLE_LEAD", true, "lead1@x.com", null, "rp-lead1", null);
        user("rp-lead0", "lead0@x.com", "LEAD", true, null, null, null, true);

        // fantasma do sync e conta do Jira duplicada
        user("ghost-acc", "ghost-acc@empresa.com", "MEMBER", true, "RP_OK", null, "ghost-acc", false);
        user("rp-j1", "j1@x.com", "MEMBER", true, null, null, "dup-jira", true);
        user("rp-j2", "j2@x.com", "MEMBER", true, null, null, "dup-jira", true);

        // linhas pmr problemáticas
        pmr("m-orphan", "RP_GHOSTSQ", "DEVELOPER", false, "orphan@x.com", null, null, null);
        pmr("m-nouser", "RP_OK", "DEVELOPER", false, "nouser@x.com", null, "usuario-que-nao-existe", null);
        pmr("m-unresolvable", "RP_OK", "DEVELOPER", false, null, null, null, null);
        pmr("m-noaccount", "RP_OK", "DEVELOPER", false, "semconta@x.com", null, null, null);

        // roster
        roster("RP_OK_free", "RP_OK", "acc-free", "free@x.com", null);                           // sem vínculo
        roster("RP_SOROSTER_a", "RP_SOROSTER", "acc-a", "a@x.com", null);                         // squad só do roster
        roster("RP_OK_dup1", "RP_OK", "acc-d1", "d1@x.com", "rp-multi");                          // mesma conta, 2 linhas
        roster("RP_OK_dup2", "RP_OK", "acc-d2", "d2@x.com", "rp-multi");
        roster("RP_OK_missing", "RP_OK", "acc-miss", "miss@x.com", "conta-que-nao-existe");       // claim inexistente
        // claim divergente: roster reivindicado, pmr da mesma pessoa sem user_id
        user("rp-div", "div@x.com", "MEMBER", true, null, null, "acc-div", true);
        pmr("m-div", "RP_OK", "DEVELOPER", false, "div@x.com", "acc-div", null, null);
        roster("RP_OK_div", "RP_OK", "acc-div", "div@x.com", "rp-div");
        // removido à mão que ainda tem pmr
        pmr("m-excl", "RP_OK", "DEVELOPER", false, "excl@x.com", "acc-excl", null, null);
        jdbc.update("INSERT INTO squad_member_exclusions (db_id, squad_id, jira_account_id, email) VALUES ('RP_OK_acc-excl', 'RP_OK', 'acc-excl', 'excl@x.com')");
    }

    @Test
    void relatorioTemUmaFixturePorInconsistenciaENaoEscreveNada() {
        relatorioFixtures();
        long pmrBefore = jdbc.queryForObject("SELECT COUNT(*) FROM project_member_roles", Long.class);
        long usersBefore = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        long rosterBefore = jdbc.queryForObject("SELECT COUNT(*) FROM squad_members", Long.class);
        List<Map<String, Object>> snapshotBefore = pmrSnapshot();

        MembershipReportDto report = reportService.generate();

        assertEquals(pmrBefore, (long) jdbc.queryForObject("SELECT COUNT(*) FROM project_member_roles", Long.class));
        assertEquals(usersBefore, (long) jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class));
        assertEquals(rosterBefore, (long) jdbc.queryForObject("SELECT COUNT(*) FROM squad_members", Long.class));
        assertEquals(snapshotBefore, pmrSnapshot(), "o relatório não escreve nada");

        assertEquals(20, report.sampleLimit());
        assertEquals(pmrBefore, report.totals().get("pmr_rows"));
        assertTrue(report.totals().containsKey("pmr_source_LEGADO"));

        var noLink = cat(report, "USERS_WITH_SQUAD_WITHOUT_LINK");
        assertTrue(has(noLink, "rp-nolink") && has(noLink, "rp-sentinel") && has(noLink, "rp-nonproject") && has(noLink, "rp-rosteronly"));
        assertEquals(1L, noLink.breakdown().get("sentinela"));
        assertEquals(2L, noLink.breakdown().get("squad_id_nao_e_projeto"));
        assertEquals(1L, noLink.breakdown().get("so_squad_members"));
        assertTrue(noLink.breakdown().get("sem_vinculo_nenhum") >= 1L);
        assertFalse(has(noLink, "rp-am"), "quem tem vínculo não é inconsistência");

        var rosterFree = cat(report, "ROSTER_WITHOUT_LINK");
        assertTrue(has(rosterFree, "RP_OK_free") && has(rosterFree, "RP_SOROSTER_a"));
        assertFalse(has(rosterFree, "RP_OK_div"), "o roster do claim divergente tem pmr correspondente");
        assertEquals(1L, rosterFree.breakdown().get("squad_so_roster"));

        assertTrue(has(cat(report, "EXCLUDED_BUT_STILL_LINKED"), "RP_OK_acc-excl"));

        var same = cat(report, "SAME_SQUAD_MULTIPLE_ROWS");
        assertTrue(has(same, "rp-twice"));
        assertEquals("lideranca_e_contribuidor", same.sample().stream().filter(r -> "rp-twice".equals(r.id())).findFirst().orElseThrow().detail());

        var multi = cat(report, "USER_IN_MULTIPLE_SQUADS");
        assertTrue(has(multi, "rp-multi"));
        assertFalse(has(multi, "rp-twice"));

        assertTrue(has(cat(report, "ROSTER_DUPLICATE_CLAIMS"), "rp-multi"));

        var noLead = cat(report, "SQUADS_WITHOUT_LEADERSHIP");
        assertTrue(has(noLead, "RP_NOLEAD") && has(noLead, "RP_NOTRIBE"));
        assertFalse(has(noLead, "RP_OK"));
        assertEquals("sem_nenhuma_pessoa", noLead.sample().stream().filter(r -> "RP_NOTRIBE".equals(r.id())).findFirst().orElseThrow().detail());

        var noAppr = cat(report, "SQUADS_WITHOUT_APPROVER");
        assertTrue(has(noAppr, "RP_NOAPPR") && has(noAppr, "RP_NOLEAD"));
        assertFalse(has(noAppr, "RP_OK"), "RP_OK tem AM com conta");

        assertTrue(has(cat(report, "PMR_USER_MISSING"), "m-nouser"));
        assertTrue(has(cat(report, "ROSTER_CLAIM_USER_MISSING"), "RP_OK_missing"));
        var divergent = cat(report, "CLAIM_DIVERGENT");
        assertTrue(has(divergent, "RP_OK_div"));
        assertEquals("pmr_sem_claim_roster_com", divergent.sample().stream().filter(r -> "RP_OK_div".equals(r.id())).findFirst().orElseThrow().detail());
        var jiraDup = cat(report, "JIRA_ACCOUNT_DUPLICATED");
        assertEquals(2L, jiraDup.count());
        assertTrue(has(jiraDup, "rp-j1") && has(jiraDup, "rp-j2"));
        assertTrue(has(cat(report, "PHANTOM_ACCOUNTS"), "ghost-acc"));

        var noAccount = cat(report, "ROSTER_EMAIL_WITHOUT_ACCOUNT");
        assertTrue(has(noAccount, "m-noaccount"));
        assertTrue(has(noAccount, "RP_OK_free"), "e-mail do roster sem conta");
        assertTrue(has(cat(report, "PMR_UNRESOLVABLE"), "m-unresolvable"));

        var leads = cat(report, "LEAD_USERS");
        assertEquals(2L, leads.count());
        assertEquals("squads_lideranca=1", leads.sample().stream().filter(r -> "rp-lead1".equals(r.id())).findFirst().orElseThrow().detail());
        assertEquals("squads_lideranca=0", leads.sample().stream().filter(r -> "rp-lead0".equals(r.id())).findFirst().orElseThrow().detail());

        var transversal = cat(report, "TRANSVERSAL_LEADERS");
        assertTrue(has(transversal, "m-lead1"));
        assertEquals("squads_alcancadas=2", transversal.sample().stream().filter(r -> "m-lead1".equals(r.id())).findFirst().orElseThrow().detail());

        assertTrue(report.categories().stream().noneMatch(c -> c.code().equals("SQUAD_KEY_CASE_VARIANTS") || c.code().equals("USER_SQUAD_DEFAULT_DIVERGENT")),
                "categorias que só faziam sentido com users.squad_id foram removidas");
        assertTrue(has(cat(report, "PMR_ORPHAN_PROJECT"), "RP_GHOSTSQ"));
        assertTrue(has(cat(report, "ROSTER_ONLY_SQUAD"), "RP_SOROSTER"));
        assertTrue(has(cat(report, "PROJECT_WITHOUT_TRIBE"), "RP_NOTRIBE"));

        // O CSV carrega o mesmo conteúdo.
        String csv = reportService.toCsv(report);
        assertTrue(csv.startsWith("﻿tipo;categoria;titulo;total;rotulo_qtd;id;email;squad;detalhe\r\n"));
        assertTrue(csv.contains("CATEGORIA;USERS_WITH_SQUAD_WITHOUT_LINK;"));
        assertTrue(csv.contains("AMOSTRA;USERS_WITH_SQUAD_WITHOUT_LINK;;;;rp-nolink;nolink@x.com;RP_NOLEAD;sem_vinculo_nenhum"));
        assertTrue(csv.contains("TOTAL;pmr_rows;;" + pmrBefore));
    }

    @Test
    void relatorioLimitaAAmostraMasContaTudo() {
        for (int i = 0; i < 25; i++) {
            user("rp-s" + i, "s" + i + "@x.com", "MEMBER", true, "Sem Time", null, null, true);
        }
        var c = cat(reportService.generate(), "USERS_WITH_SQUAD_WITHOUT_LINK");
        assertEquals(25L, c.count());
        assertEquals(20, c.sample().size());
        assertTrue(c.truncated());
        assertEquals(25L, c.breakdown().get("sentinela"));
    }

    @Test
    void relatorioEmBancoVazioNaoQuebra() {
        MembershipReportDto report = reportService.generate();
        assertEquals(0L, report.totals().get("pmr_rows"));
        assertTrue(report.categories().stream().allMatch(c -> c.count() == 0 && c.sample().isEmpty() && !c.truncated()));
    }

    @Test
    void csvNeutralizaInjecaoDeFormulaEEscapaSeparador() {
        assertEquals("'=cmd|' /c calc'!A1", MembershipReportService.csv("=cmd|' /c calc'!A1"));
        assertEquals("'+1", MembershipReportService.csv("+1"));
        assertEquals("\"a;b\"", MembershipReportService.csv("a;b"));
        assertEquals("\"diz \"\"oi\"\"\"", MembershipReportService.csv("diz \"oi\""));
        assertEquals("", MembershipReportService.csv(null));
    }

    // ---------- autorização do endpoint ----------

    @Test
    void endpointDoRelatorioSoParaAdmin() throws Exception {
        user("rp-admin", "rp-admin@totvs.com.br", "ADMIN", true, null, null, null, true);
        user("rp-comum", "rp-comum@totvs.com.br", "MEMBER", true, null, null, null, true);
        user("rp-lider", "rp-lider@totvs.com.br", "LEAD", true, null, null, null, true);
        project("RP_OK", "T1");
        pmr("m-x", "RP_OK", "AGILE_MASTER", true, "rp-comum@totvs.com.br", null, "rp-comum", null);

        mockMvc.perform(get("/api/admin/membership/report")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/membership/report").header("Authorization", token("rp-comum", "MEMBER"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/membership/report").header("Authorization", token("rp-lider", "LEAD"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/membership/report.csv").header("Authorization", token("rp-comum", "MEMBER"))).andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/membership/report").header("Authorization", token("rp-admin", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampleLimit").value(20))
                .andExpect(jsonPath("$.totals.pmr_rows").value(1))
                .andExpect(jsonPath("$.categories.length()").value(org.hamcrest.Matchers.greaterThan(15)))
                .andExpect(jsonPath("$.categories[?(@.code=='SQUADS_WITHOUT_LEADERSHIP')].count").exists());

        mockMvc.perform(get("/api/admin/membership/report.csv").header("Authorization", token("rp-admin", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("membership-report.csv")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("CATEGORIA;USERS_WITH_SQUAD_WITHOUT_LINK;")));
    }
}
