package com.agilespace.backend.service;

import com.agilespace.backend.domain.Invite;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtTokenUtil;
import com.agilespace.backend.security.PasswordUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Etapa 5 do vínculo (passo 1): a coluna {@code users.squad_id} PERMANECE no banco, mas nenhum fluxo a lê ou grava.
 * Contra um Postgres real, com {@code ddl-auto=validate} (a subida do contexto já prova que o schema real fecha com a
 * entidade sem o campo) e os modos {@code new} ligados, como na homologação: registro, login (inclusive o fallback do
 * roster), troca de squad, "Sou eu", convite, roster, perfil e criação de squad nova deixam a coluna como estava
 * (NULL para contas novas). Sem Docker o teste é ignorado.
 */
@SpringBootTest(properties = {"app.membership.mode=new", "app.membership.authz=new", "app.membership.bootstrap=on"})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class UsersSquadIdRetiredIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final String PASSWORD = "senha-forte-123";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenUtil jwtTokenUtil;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository userRepository;
    @Autowired private MembershipCache cache;
    @Autowired private InviteService inviteService;
    @Autowired private SquadService squadService;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE project_member_roles, squad_members, squad_member_exclusions, squads, projects, link_requests, invites, users CASCADE");
        cache.evictAll(); // TRUNCATE passa por fora do JPA
    }

    // ---------- fixtures ----------

    private String token(String id) {
        return "Bearer " + jwtTokenUtil.generateToken(id, id + "@totvs.com.br", "Pessoa " + id, "MEMBER", null, null, null);
    }

    private User user(String id, String defaultProject, boolean withPassword) {
        return userRepository.save(User.builder().id(id).email(id + "@totvs.com.br").name("Pessoa " + id).role("MEMBER").active(true)
                .passwordHash(withPassword ? PasswordUtil.hashPassword(PASSWORD) : null).defaultProjectId(defaultProject).build());
    }

    private void project(String id) {
        jdbc.update("INSERT INTO projects (id, name, tribe_name, segment_name) VALUES (?, ?, 'Tribo A', 'Seg A')", id, "Squad " + id);
    }

    private void pmr(String id, String project, String roleKey, String roleName, String email, String userId) {
        jdbc.update("INSERT INTO project_member_roles (id, project_id, role_name, role_key, display_name, email, user_id, is_leadership, source) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, false, 'MANUAL')", id, project, roleName, roleKey, "Pessoa " + id, email, userId);
    }

    private String squadIdColumn(String userId) {
        return jdbc.queryForObject("SELECT squad_id FROM users WHERE id = ?", String.class, userId);
    }

    private String defaultProjectColumn(String userId) {
        return jdbc.queryForObject("SELECT default_project_id FROM users WHERE id = ?", String.class, userId);
    }

    private String squadIdColumnByEmail(String email) {
        return jdbc.queryForObject("SELECT squad_id FROM users WHERE email = ?", String.class, email);
    }

    private JsonNode send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) throws Exception {
        String body = mockMvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    // ---------- o schema real fecha com a entidade sem o campo ----------

    @Test
    void aColunaPermaneceNoBanco_eAEntidadeNaoTemOCampo() {
        Integer columns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'users' AND column_name = 'squad_id'", Integer.class);
        assertEquals(1, columns, "passo 1 é reversível: a coluna não foi tocada (o DROP é o passo 2, com backup)");
        assertThrows(NoSuchFieldException.class, () -> User.class.getDeclaredField("squadId"));
    }

    // ---------- entrada ----------

    @Test
    void registro_naoGravaSquadId_masGravaAPreferencia() throws Exception {
        project("SQ1");
        pmr("r-ana", "SQ1", "DEVELOPER", "Developer", "ana@totvs.com.br", null);

        JsonNode session = send(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ana@totvs.com.br\",\"name\":\"Ana\",\"password\":\"" + PASSWORD + "\"}"));

        assertEquals("SQ1", session.get("activeProjectId").asText());
        assertNull(squadIdColumnByEmail("ana@totvs.com.br"));
        assertEquals("SQ1", jdbc.queryForObject("SELECT default_project_id FROM users WHERE email = 'ana@totvs.com.br'", String.class));
    }

    @Test
    void registroDeContaFantasma_naoToca_aColunaAntiga() throws Exception {
        project("SQ1");
        pmr("r-bia", "SQ1", "QA", "QA", "bia@totvs.com.br", null);
        // Conta pré-provisionada pelo sync do Jira, com um valor ANTIGO na coluna: continua exatamente como estava.
        jdbc.update("INSERT INTO users (id, email, name, role, auth_provider, is_active, is_guest, squad_id) "
                + "VALUES ('bia', 'bia@totvs.com.br', 'Bia', 'MEMBER', 'LOCAL', true, false, 'VELHA')");

        send(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"bia@totvs.com.br\",\"name\":\"Bia\",\"password\":\"" + PASSWORD + "\"}"));

        assertEquals("VELHA", squadIdColumn("bia"), "ninguém mais grava a coluna, nem para 'completar'");
    }

    @Test
    void login_preencheAPreferenciaPeloVinculo_semGravarSquadId() throws Exception {
        project("SQ1");
        user("carla", null, true);
        pmr("r-carla", "SQ1", "DEVELOPER", "Developer", "carla@totvs.com.br", null);

        JsonNode session = send(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"carla@totvs.com.br\",\"password\":\"" + PASSWORD + "\"}"));

        assertEquals("SQ1", session.get("activeProjectId").asText());
        assertEquals("SQ1", defaultProjectColumn("carla"));
        assertNull(squadIdColumn("carla"));
    }

    @Test
    void login_fallbackDoRoster_gravaSoAPreferencia_eNaoDaAcesso() throws Exception {
        user("davi", null, true);
        jdbc.update("INSERT INTO squad_members (db_id, squad_id, jira_account_id, display_name, email, role) "
                + "VALUES ('ROSTERSQ_davi', 'ROSTERSQ', 'jira-davi', 'Davi', 'davi@totvs.com.br', 'Developer')");

        JsonNode session = send(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"davi@totvs.com.br\",\"password\":\"" + PASSWORD + "\"}"));

        assertEquals("ROSTERSQ", defaultProjectColumn("davi"), "preferência vinda do roster, como antes");
        assertNull(squadIdColumn("davi"));
        assertTrue(session.get("accessibleProjects").isEmpty(), "sem linha em project_member_roles não há vínculo");
        assertTrue(session.get("legacyOnly").asBoolean());
    }

    // ---------- troca de squad, "Sou eu", convite ----------

    @Test
    void trocarDeSquad_gravaSoDefaultProjectId() throws Exception {
        project("SQ1");
        project("SQ2");
        user("eva", "SQ1", false);
        pmr("r-eva1", "SQ1", "DEVELOPER", "Developer", "eva@totvs.com.br", null);
        pmr("r-eva2", "SQ2", "QA", "QA", "eva@totvs.com.br", null);

        JsonNode session = send(post("/api/auth/switch-project").param("projectId", "SQ2").header("Authorization", token("eva")));

        assertEquals("SQ2", session.get("activeProjectId").asText());
        assertEquals("SQ2", defaultProjectColumn("eva"));
        assertNull(squadIdColumn("eva"));
    }

    @Test
    void souEu_ligaPeloVinculo_semSquadIdNemClaimedByUid() throws Exception {
        project("SQ1");
        user("fabio", null, false);
        pmr("r-fabio", "SQ1", "DEVELOPER", "Developer", "fabio@totvs.com.br", null);
        jdbc.update("INSERT INTO squad_members (db_id, squad_id, jira_account_id, display_name, email, role) "
                + "VALUES ('SQ1_fabio', 'SQ1', 'jira-fabio', 'Pessoa fabio', 'fabio@totvs.com.br', 'Developer')");

        JsonNode session = send(post("/api/onboarding/claim/r-fabio").header("Authorization", token("fabio")));

        assertEquals("SQ1", session.get("activeProjectId").asText());
        assertEquals("fabio", jdbc.queryForObject("SELECT user_id FROM project_member_roles WHERE id = 'r-fabio'", String.class));
        assertNull(squadIdColumn("fabio"));
        assertNull(jdbc.queryForObject("SELECT claimed_by_uid FROM squad_members WHERE db_id = 'SQ1_fabio'", String.class),
                "claimed_by_uid deixou de ser gravado pelo vínculo");
    }

    @Test
    void aceitarConvite_criaOVinculo_semSquadId() throws Exception {
        project("SQ1");
        user("gabi", null, false);
        Invite invite = inviteService.createInvite("SQ1", "Developer", null, "admin");

        JsonNode session = send(post("/api/invites/" + invite.getToken() + "/accept").header("Authorization", token("gabi")));

        assertEquals("SQ1", session.get("activeProjectId").asText());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM project_member_roles WHERE user_id = 'gabi' AND project_id = 'SQ1'", Integer.class));
        assertEquals("SQ1", defaultProjectColumn("gabi"));
        assertNull(squadIdColumn("gabi"));
    }

    // ---------- importação do Jira e roster ----------

    @Test
    void removerDoRoster_naoRegravaAContaDeQuemSai() {
        user("hugo", "PROJ1", false);
        jdbc.update("UPDATE users SET jira_account_id = 'jira-hugo', squad_id = 'ANTIGA' WHERE id = 'hugo'");
        squadService.saveMember("PROJ1", "jira-hugo", SquadMember.builder().displayName("Hugo").email("hugo@totvs.com.br").build());
        assertEquals("ANTIGA", squadIdColumn("hugo"), "salvar membro não sobrescreve mais a coluna (last-write-wins acabou)");

        squadService.deleteMember("PROJ1", "jira-hugo");

        assertEquals("ANTIGA", squadIdColumn("hugo"), "quem saiu do roster não é regravado (valor antigo intocado)");
        assertEquals("PROJ1", defaultProjectColumn("hugo"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM squad_members WHERE jira_account_id = 'jira-hugo'", Integer.class));
    }

    @Test
    void salvarOuRemoverMembroDoRoster_naoMexeNaColuna() {
        user("iris", null, false);
        jdbc.update("UPDATE users SET jira_account_id = 'jira-iris' WHERE id = 'iris'");

        squadService.saveMember("SQX", "jira-iris", SquadMember.builder().displayName("Íris").email("iris@totvs.com.br").build());
        assertNull(squadIdColumn("iris"));

        squadService.deleteMember("SQX", "jira-iris");
        assertNull(squadIdColumn("iris"));
    }

    // ---------- perfil e squad nova ----------

    @Test
    void perfil_ignoraSquadIdDeClienteAntigo_eDevolveASquadAtivaDoVinculo() throws Exception {
        project("SQ1");
        project("SQ2");
        user("joao", "SQ1", false);
        pmr("r-joao1", "SQ1", "DEVELOPER", "Developer", "joao@totvs.com.br", null);
        pmr("r-joao2", "SQ2", "QA", "QA", "joao@totvs.com.br", null);

        // Cliente antigo: manda squadId de uma squad COM vínculo (SQ2) e outra SEM vínculo; nada de 400/403, nada muda.
        for (String squad : List.of("SQ2", "SQ-QUALQUER")) {
            JsonNode saved = send(post("/api/users").header("Authorization", token("joao")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"id\":\"joao\",\"name\":\"João Novo\",\"squadId\":\"" + squad + "\"}"));
            assertEquals("João Novo", saved.get("name").asText());
            assertEquals("SQ1", saved.get("squadId").asText(), "derivado da squad ativa validada, não do corpo");
        }
        assertEquals("SQ1", defaultProjectColumn("joao"), "a preferência não muda por saveUser");
        assertNull(squadIdColumn("joao"));

        JsonNode me = send(get("/api/users/joao").header("Authorization", token("joao")));
        assertEquals("SQ1", me.get("squadId").asText());
    }

    @Test
    void perfil_squadIdVemDoVinculo_naoDaPreferenciaSolta_eFicaAusenteSemVinculo() throws Exception {
        project("SQ1");
        user("kelly", "SQUAD-SOLTA", false);
        pmr("r-kelly", "SQ1", "DEVELOPER", "Developer", "kelly@totvs.com.br", null);
        user("leo", "SQUAD-SOLTA", false);

        assertEquals("SQ1", send(get("/api/users/kelly").header("Authorization", token("kelly"))).get("squadId").asText());
        JsonNode leo = send(get("/api/users/leo").header("Authorization", token("leo")));
        assertTrue(leo.get("squadId") == null || leo.get("squadId").isNull(), "sem vínculo, sem squadId");
    }

    @Test
    void squadNova_vinculaPelaPreferencia_semSquadId() throws Exception {
        user("marta", null, false);

        mockMvc.perform(post("/api/squads/NOVA").header("Authorization", token("marta")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ceremonyMode\":\"manual\"}")).andExpect(status().is2xxSuccessful());

        assertEquals("NOVA", defaultProjectColumn("marta"), "o bootstrap grava só a preferência");
        assertNull(squadIdColumn("marta"));
    }
}
