package com.agilespace.backend.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtTokenUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bootstrap de squad nova em ambiente limpo (HTTP + Postgres real) nos três modos de {@code app.membership.authz}.
 * Em {@code new} (com app.membership.bootstrap=on) o primeiro uso de uma squad vazia cria o vínculo de liderança (a pessoa continua passando na chamada
 * seguinte); em {@code legacy} e {@code shadow} só grava {@code default_project_id}, como sempre (a coluna users.squad_id foi aposentada). Sem Docker é ignorado.
 */
@Testcontainers(disabledWithoutDocker = true)
class SquadBootstrapIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    abstract static class Base {
        @Autowired JdbcTemplate jdbc;
        @Autowired MockMvc mockMvc;
        @Autowired JwtTokenUtil jwtTokenUtil;
        @Autowired UserRepository userRepository;
        @Autowired MembershipCache cache;

        private ListAppender<ILoggingEvent> logs;
        private Logger logger;

        @BeforeEach
        void clean() {
            jdbc.execute("TRUNCATE project_member_roles, squad_members, squad_member_exclusions, squads, projects, link_requests, users CASCADE");
            cache.evictAll();
            logs = new ListAppender<>();
            logs.start();
            logger = (Logger) LoggerFactory.getLogger(SquadBootstrapService.class);
            logger.addAppender(logs);
            logger.setLevel(Level.INFO);
            // Ambiente limpo: contas sem squad, sem vínculo, sem roster.
            user("p1", "MEMBER");
            user("p2", "MEMBER");
            user("admin", "ADMIN");
        }

        @AfterEach
        void detach() {
            logger.detachAppender(logs);
        }

        String token(String id, String role) {
            return "Bearer " + jwtTokenUtil.generateToken(id, id + "@totvs.com.br", "Pessoa " + id, role, null, null, null);
        }

        User user(String id, String role) {
            return userRepository.save(User.builder().id(id).email(id + "@totvs.com.br").name("Pessoa " + id).role(role).active(true).build());
        }

        /** Gestão (config/horas por pessoa): é onde o bootstrap entra em ambiente limpo. */
        int manage(String squad, String user, String role) throws Exception {
            return mockMvc.perform(get("/api/squads/" + squad + "/member-metrics").header("Authorization", token(user, role)))
                    .andReturn().getResponse().getStatus();
        }

        int read(String squad, String user, String role) throws Exception {
            return mockMvc.perform(get("/api/squads/" + squad + "/members").header("Authorization", token(user, role)))
                    .andReturn().getResponse().getStatus();
        }

        List<Map<String, Object>> pmrOf(String squad) {
            return jdbc.queryForList("SELECT user_id, role_key, is_leadership, source FROM project_member_roles WHERE project_id = ?", squad);
        }

        String squadIdOf(String user) {
            return jdbc.queryForObject("SELECT squad_id FROM users WHERE id = ?", String.class, user);
        }

        String defaultOf(String user) {
            return jdbc.queryForObject("SELECT default_project_id FROM users WHERE id = ?", String.class, user);
        }

        int projectsNamed(String squad) {
            return jdbc.queryForObject("SELECT count(*) FROM projects WHERE id = ?", Integer.class, squad);
        }

        List<String> bootstrapLogs() {
            return logs.list.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.startsWith("authz_bootstrap")).toList();
        }
    }

    @Nested
    @SpringBootTest(properties = {"app.membership.authz=new", "app.membership.mode=new", "app.membership.bootstrap=on"})
    @AutoConfigureMockMvc
    class ModoNew extends Base {

        @Test
        void primeiroUsuarioDeSquadVazia_viraLiderancaEContinuaPassando() throws Exception {
            assertEquals(200, manage("NOVA", "p1", "MEMBER"), "primeiro uso: concede");

            List<Map<String, Object>> rows = pmrOf("NOVA");
            assertEquals(1, rows.size());
            assertEquals("p1", rows.get(0).get("user_id"));
            assertEquals("AGILE_MASTER", rows.get(0).get("role_key"));
            assertEquals(Boolean.TRUE, rows.get(0).get("is_leadership"));
            assertEquals("MANUAL", rows.get(0).get("source"));
            assertEquals(1, projectsNamed("NOVA"));
            assertEquals("NOVA", defaultOf("p1"), "grava a preferência default_project_id");
            assertNull(squadIdOf("p1"), "users.squad_id aposentada (Etapa 5): nunca mais gravada");
            assertEquals(List.of("authz_bootstrap user=p1 squad=NOVA"), bootstrapLogs());

            // Segunda chamada: passa pelo VÍNCULO (o cache de vínculos foi invalidado pelo listener JPA, senão a
            // consulta anterior, vazia, ainda estaria em cache e a pessoa seria negada).
            assertEquals(200, manage("NOVA", "p1", "MEMBER"));
            assertEquals(200, read("NOVA", "p1", "MEMBER"));
            assertEquals(1, pmrOf("NOVA").size(), "não duplica o vínculo");
            assertEquals(1, bootstrapLogs().size(), "só o primeiro uso é auditado como bootstrap");

            mockMvc.perform(get("/api/me/memberships").header("Authorization", token("p1", "MEMBER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.memberships[0].projectId").value("NOVA"))
                    .andExpect(jsonPath("$.memberships[0].leadership").value(true));
        }

        @Test
        void segundoUsuarioSemVinculoNaSquadJaComLideranca_e403() throws Exception {
            assertEquals(200, manage("NOVA", "p1", "MEMBER"));

            assertEquals(403, manage("NOVA", "p2", "MEMBER"));
            assertEquals(403, read("NOVA", "p2", "MEMBER"));
            assertEquals(0, pmrOf("NOVA").stream().filter(r -> "p2".equals(r.get("user_id"))).count());
            assertNull(squadIdOf("p2"), "negado: nada é gravado na pessoa");
            assertEquals(1, bootstrapLogs().size());
        }

        @Test
        void squadComVinculosMasSemLinhaDeSquadNemRoster_naoConcedeANinguem() throws Exception {
            // Importada do Jira: só project_member_roles (sem linha em squads nem roster). A condição legada diria "vazia".
            jdbc.update("INSERT INTO projects (id, name) VALUES ('JIRAX', 'Jira X')");
            user("am", "MEMBER");
            jdbc.update("INSERT INTO project_member_roles (id, project_id, role_name, role_key, display_name, email, user_id, is_leadership, source) "
                    + "VALUES ('r1', 'JIRAX', 'Agile Master', 'AGILE_MASTER', 'AM', 'am@totvs.com.br', 'am', true, 'JIRA')");

            assertEquals(403, manage("JIRAX", "p2", "MEMBER"));
            assertEquals(403, manage("jirax", "p2", "MEMBER"), "chave com outra caixa também não");
            assertNull(squadIdOf("p2"));
            assertEquals(1, pmrOf("JIRAX").size());
            assertTrue(bootstrapLogs().isEmpty());
        }

        @Test
        void projetoCadastradoSemNinguem_naoEhPrimeiroUso() throws Exception {
            jdbc.update("INSERT INTO projects (id, name) VALUES ('VAZIO', 'Vazio')");

            assertEquals(403, manage("VAZIO", "p1", "MEMBER"));
            assertTrue(pmrOf("VAZIO").isEmpty());
        }

        @Test
        void quemJaTemVinculoEmOutraSquad_naoUsaOBootstrapParaCriarOutra() throws Exception {
            assertEquals(200, manage("NOVA", "p1", "MEMBER"));
            // squad_id perdido (ex.: limpeza), mas o vínculo continua: a regra nova não o trata como pessoa sem time.
            jdbc.update("UPDATE users SET squad_id = NULL, default_project_id = NULL WHERE id = 'p1'");

            assertEquals(200, manage("NOVA", "p1", "MEMBER"), "continua passando pelo vínculo");
            assertEquals(403, manage("OUTRA", "p1", "MEMBER"));
            assertTrue(pmrOf("OUTRA").isEmpty());
            assertEquals(0, projectsNamed("OUTRA"));
        }

        @Test
        void adminInalterado_naoGanhaVinculoNemGravaSquad() throws Exception {
            assertEquals(200, manage("NOVA", "admin", "ADMIN"));
            assertEquals(200, read("NOVA", "admin", "ADMIN"));
            assertTrue(pmrOf("NOVA").isEmpty());
            assertNull(squadIdOf("admin"));
            assertTrue(bootstrapLogs().isEmpty());
            // E depois dele, a primeira pessoa sem time ainda faz o primeiro uso (o admin não "ocupou" a squad).
            assertEquals(200, manage("NOVA", "p1", "MEMBER"));
        }
    }

    /** authz=new com o bootstrap no padrao (off): squad vazia e 403 para nao-ADMIN e nada e gravado. */
    @Nested
    @SpringBootTest(properties = {"app.membership.authz=new", "app.membership.mode=new"})
    @AutoConfigureMockMvc
    class ModoNewBootstrapDesligado extends Base {

        @Test
        void squadVazia_e403_eNadaEGravado() throws Exception {
            assertEquals(403, manage("NOVA", "p1", "MEMBER"));
            assertEquals(403, read("NOVA", "p1", "MEMBER"));
            assertTrue(pmrOf("NOVA").isEmpty());
            assertEquals(0, projectsNamed("NOVA"));
            assertNull(squadIdOf("p1"));
            assertTrue(bootstrapLogs().isEmpty());
        }

        @Test
        void adminContinuaPassando() throws Exception {
            assertEquals(200, manage("NOVA", "admin", "ADMIN"));
            assertTrue(pmrOf("NOVA").isEmpty());
        }
    }

    @Nested
    @SpringBootTest(properties = "app.membership.authz=legacy")
    @AutoConfigureMockMvc
    class ModoLegacy extends Base {

        @Test
        void gravaSoAPreferencia_semCriarVinculo_comoAntes() throws Exception {
            assertEquals(200, manage("NOVA", "p1", "MEMBER"));

            assertEquals("NOVA", defaultOf("p1"));
            assertNull(squadIdOf("p1"));
            assertTrue(pmrOf("NOVA").isEmpty(), "legacy não cria vínculo");
            assertEquals(0, projectsNamed("NOVA"));
            assertEquals(200, manage("NOVA", "p1", "MEMBER"), "segue passando por default_project_id");
            assertEquals(200, manage("NOVA", "admin", "ADMIN"));
        }

        @Test
        void squadQueJaTemRoster_naoEhBootstrap() throws Exception {
            jdbc.update("INSERT INTO squad_members (db_id, squad_id, jira_account_id, display_name, role) VALUES ('X_1', 'COMTIME', 'jira-1', 'Alguém', 'Developer')");

            assertEquals(403, manage("COMTIME", "p1", "MEMBER"));
            assertNull(squadIdOf("p1"));
        }
    }

    @Nested
    @SpringBootTest(properties = "app.membership.authz=shadow")
    @AutoConfigureMockMvc
    class ModoShadow extends Base {

        @Test
        void identicoAoLegacy_semCriarVinculo() throws Exception {
            assertEquals(200, manage("NOVA", "p1", "MEMBER"));

            assertEquals("NOVA", defaultOf("p1"));
            assertNull(squadIdOf("p1"));
            assertTrue(pmrOf("NOVA").isEmpty(), "shadow não grava vínculo: só observa");
            assertEquals(0, projectsNamed("NOVA"));
            assertEquals(200, manage("NOVA", "p1", "MEMBER"));
        }
    }
}
