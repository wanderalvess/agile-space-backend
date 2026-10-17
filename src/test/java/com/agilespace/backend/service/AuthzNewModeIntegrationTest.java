package com.agilespace.backend.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.SquadMemberRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtTokenUtil;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
 * Etapa 2 do vínculo ponta a ponta (HTTP + Postgres real) com a chave virada: {@code app.membership.authz=new} decide
 * pelos vínculos e {@code shadow} serve o legado registrando a divergência. O contexto sobe uma vez por modo.
 * Sem Docker o teste é ignorado.
 */
@Testcontainers(disabledWithoutDocker = true)
class AuthzNewModeIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** Fixture e utilitários compartilhados pelos dois modos. */
    abstract static class Base {
        @Autowired JdbcTemplate jdbc;
        @Autowired MockMvc mockMvc;
        @Autowired JwtTokenUtil jwtTokenUtil;
        @Autowired UserRepository userRepository;
        @Autowired SquadMemberRepository squadMemberRepository;
        @Autowired MembershipCache cache;
        @Autowired AuthzShadow authzShadow;

        @BeforeEach
        void clean() {
            jdbc.execute("TRUNCATE project_member_roles, squad_members, squad_member_exclusions, projects, link_requests, users CASCADE");
            cache.evictAll();
            scenario();
        }

        String token(String id, String role) {
            return "Bearer " + jwtTokenUtil.generateToken(id, id + "@totvs.com.br", "Pessoa " + id, role, null, null, null);
        }

        User user(String id, String role, String squadId, String jobTitle) {
            return userRepository.save(User.builder().id(id).email(id + "@totvs.com.br").name("Pessoa " + id).role(role).active(true)
                    .defaultProjectId(squadId).jobTitle(jobTitle).build());
        }

        void project(String id, String name, String tribe) {
            jdbc.update("INSERT INTO projects (id, name, tribe_name, segment_name) VALUES (?, ?, ?, ?)", id, name, tribe, "Seg " + tribe);
        }

        void pmr(String id, String project, String key, String roleName, boolean lead, String userId) {
            jdbc.update("INSERT INTO project_member_roles (id, project_id, role_name, role_key, display_name, email, user_id, is_leadership, source) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL')", id, project, roleName, key, "Nome " + id, userId + "@totvs.com.br", userId, lead);
        }

        void roster(String squad, String userId) {
            squadMemberRepository.save(SquadMember.builder().dbId(squad + "_" + userId).squadId(squad).jiraAccountId("jira-" + userId)
                    .displayName("Pessoa " + userId).email(userId + "@totvs.com.br").role("Developer").build());
        }

        void scenario() {
            project("SQ1", "Alfa", "Tribo A");
            project("SQ2", "Beta (sem liderança)", "Tribo B");
            user("am", "MEMBER", "SQ1", null);
            pmr("r-am", "SQ1", "AGILE_MASTER", "Agile Master", true, "am");
            user("dev", "MEMBER", "SQ1", null);
            pmr("r-dev", "SQ1", "DEVELOPER", "Developer", false, "dev");
            user("roster", "MEMBER", null, null);
            roster("SQ1", "roster");
            user("solto", "MEMBER", "SQ1", null);
            user("cargo", "MEMBER", "SQ1", "Tech Lead");
            user("dev2", "MEMBER", "SQ2", null);
            pmr("r-dev2", "SQ2", "DEVELOPER", "Developer", false, "dev2");
            user("roster2", "MEMBER", null, null);
            roster("SQ2", "roster2");
            user("lead", "LEAD", null, null);
            user("admin", "ADMIN", null, null);
        }

        int read(String squad, String user, String role) throws Exception {
            return mockMvc.perform(get("/api/squads/" + squad + "/members").header("Authorization", token(user, role)))
                    .andReturn().getResponse().getStatus();
        }

        int manage(String squad, String user, String role) throws Exception {
            return mockMvc.perform(get("/api/squads/" + squad + "/member-metrics").header("Authorization", token(user, role)))
                    .andReturn().getResponse().getStatus();
        }

        int invite(String squad, String user, String role) throws Exception {
            return mockMvc.perform(post("/api/squads/" + squad + "/invites").header("Authorization", token(user, role))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"roleName\":\"Developer\"}")).andReturn().getResponse().getStatus();
        }
    }

    @Nested
    @SpringBootTest(properties = "app.membership.authz=new")
    @AutoConfigureMockMvc
    class ModoNew extends Base {

        @Test
        void chaveVirada_decidemOsVinculos() throws Exception {
            assertEquals(AuthzMode.NEW, authzShadow.mode());

            // leitura
            assertEquals(200, read("SQ1", "am", "MEMBER"));
            assertEquals(200, read("SQ1", "dev", "MEMBER"));
            assertEquals(403, read("SQ2", "dev", "MEMBER"));
            assertEquals(403, read("SQ1", "roster", "MEMBER"), "responsável de issue (roster-only) não lê a squad");
            assertEquals(403, read("SQ1", "solto", "MEMBER"), "users.squad_id solto não dá acesso");
            assertEquals(403, read("SQ1", "cargo", "MEMBER"), "cargo de liderança não dá acesso");
            assertEquals(200, read("SQ1", "lead", "LEAD"), "LEAD mantém acesso total até a Etapa 4");
            assertEquals(200, read("SQ2", "admin", "ADMIN"));

            // gestão
            assertEquals(200, manage("SQ1", "am", "MEMBER"));
            assertEquals(403, manage("SQ1", "dev", "MEMBER"));
            assertEquals(403, manage("SQ1", "cargo", "MEMBER"), "jobTitle não é autoridade");
            assertEquals(200, manage("SQ2", "dev2", "MEMBER"), "squad sem liderança: quem tem vínculo configura (bootstrap)");
            assertEquals(403, manage("SQ2", "roster2", "MEMBER"), "...mas o roster-only não");
            assertEquals(200, manage("SQ2", "lead", "LEAD"));

            // convites
            assertEquals(201, invite("SQ1", "am", "MEMBER"));
            assertEquals(403, invite("SQ1", "dev", "MEMBER"));
            assertEquals(403, invite("SQ1", "cargo", "MEMBER"));
            assertEquals(201, invite("SQ1", "admin", "ADMIN"));
        }

        @Test
        void authzDiffContinuaDizendoOQueMudaEmNew() throws Exception {
            mockMvc.perform(get("/api/admin/membership/authz-diff").header("Authorization", token("admin", "ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.mode").value("new"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.totals.LOSS").value(7));
        }
    }

    @Nested
    @SpringBootTest(properties = "app.membership.authz=shadow")
    @AutoConfigureMockMvc
    class ModoShadow extends Base {

        private ListAppender<ILoggingEvent> logs;
        private Logger logger;
        @Autowired private MeterRegistry meters;

        @BeforeEach
        void attach() {
            logs = new ListAppender<>();
            logs.start();
            logger = (Logger) LoggerFactory.getLogger(AuthzShadow.class);
            logger.addAppender(logs);
            logger.setLevel(Level.INFO);
        }

        @AfterEach
        void detach() {
            logger.detachAppender(logs);
        }

        @Test
        void sombraServeOLegado_eRegistraADivergencia() throws Exception {
            assertEquals(AuthzMode.SHADOW, authzShadow.mode());

            assertEquals(200, read("SQ1", "roster", "MEMBER"), "decisão = legado");
            assertEquals(200, read("SQ1", "solto", "MEMBER"));
            assertEquals(201, invite("SQ1", "cargo", "MEMBER"));
            assertEquals(403, read("SQ2", "dev", "MEMBER"));
            assertEquals(200, read("SQ1", "dev", "MEMBER"));

            List<String> lines = logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.startsWith("authz_divergence")).toList();
            assertTrue(lines.contains("authz_divergence user=roster check=matchesSquad squad=SQ1 legacy=true new=false reason=ONLY_ROSTER"), lines.toString());
            assertTrue(lines.contains("authz_divergence user=solto check=matchesSquad squad=SQ1 legacy=true new=false reason=ONLY_SQUAD_ID"), lines.toString());
            assertTrue(lines.contains("authz_divergence user=cargo check=invite squad=SQ1 legacy=true new=false reason=ONLY_JOB_TITLE"), lines.toString());
            assertTrue(lines.stream().noneMatch(l -> l.contains("@")));
            assertTrue(meters.counter(AuthzShadow.METRIC, "check", "matchesSquad", "direction", "LOSS").count() >= 2.0);
        }
    }
}
