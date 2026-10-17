package com.agilespace.backend.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.RequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * O caminho do PRIMEIRO usuário numa instalação limpa (banco vazio) com {@code app.membership.mode=new} e
 * {@code app.membership.authz=new} (bootstrap no padrão, desligado), ponta a ponta por HTTP + Postgres real: cadastro,
 * sessão sem vínculo, membro comum barrado, primeiro ADMIN criando a equipe, convite, telas da squad. Fixa também o que
 * NÃO funciona sem ADMIN (criar equipe e inventar squad), para que uma mudança desse comportamento apareça como falha
 * de teste. Sem Docker é ignorado.
 */
@SpringBootTest(properties = {"app.membership.mode=new", "app.membership.authz=new"})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PrimeiroUsuarioInstalacaoLimpaTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired MembershipCache cache;

    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void instalacaoLimpa() {
        jdbc.execute("TRUNCATE project_member_roles, squad_members, squad_member_exclusions, squads, projects, invites, link_requests, users CASCADE");
        cache.evictAll();
        logs = new ListAppender<>();
        logs.start();
        logger = (Logger) LoggerFactory.getLogger(SquadAccessService.class);
        logger.addAppender(logs);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
    }

    private JsonNode register(String who) throws Exception {
        String body = "{\"email\":\"" + who + "@totvs.com.br\",\"name\":\"Pessoa " + who + "\",\"password\":\"senha-forte-123\"}";
        return mapper.readTree(mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode login(String who) throws Exception {
        String body = "{\"email\":\"" + who + "@totvs.com.br\",\"password\":\"senha-forte-123\"}";
        return mapper.readTree(mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString());
    }

    private static String bearer(JsonNode session) {
        return "Bearer " + session.get("token").asText();
    }

    private int status(RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private JsonNode memberships(String auth) throws Exception {
        return mapper.readTree(mockMvc.perform(get("/api/me/memberships").header("Authorization", auth)).andReturn().getResponse().getContentAsString());
    }

    @Test
    void membroComumSemVinculo_naoEntraPorBootstrap_entraPeloConviteDoPrimeiroAdmin() throws Exception {
        JsonNode ana = register("ana");
        String auth = bearer(ana);

        // Conta nova e limpa: sem vínculo, sem rastro (vai para o onboarding, não é "só legado").
        assertEquals(0, ana.get("accessibleProjects").size());
        assertTrue(ana.path("legacyOnly").isMissingNode() || ana.get("legacyOnly").isNull());
        assertEquals(0, memberships(auth).get("memberships").size());
        assertFalse(memberships(auth).get("legacyOnly").asBoolean());
        assertEquals(200, status(get("/api/onboarding/suggestions").header("Authorization", auth)));

        // Nada de criar equipe nem inventar squad pelo bootstrap (desligado); nada é gravado.
        assertEquals(403, status(post("/api/projects").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"TIMEA\",\"name\":\"Time A\"}")), "membro comum não cria equipe");
        assertEquals(403, status(post("/api/squads/TIMEA").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ceremonyMode\":\"manual\"}")), "bootstrap desligado");
        assertEquals(403, status(get("/api/squads/TIMEA/member-metrics").header("Authorization", auth)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM project_member_roles", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM projects", Integer.class));
        assertNull(jdbc.queryForObject("SELECT squad_id FROM users WHERE email = 'ana@totvs.com.br'", String.class));

        // O primeiro ADMIN (SQL, README) cria a equipe e entra como liderança (Agile Master).
        register("root");
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE email = 'root@totvs.com.br'");
        String root = bearer(login("root"));
        assertEquals(200, status(post("/api/projects").header("Authorization", root).contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"TIMEB\",\"name\":\"Time B\"}")));
        JsonNode rootM = memberships(root).get("memberships").get(0);
        assertEquals("TIMEB", rootM.get("projectId").asText());
        assertEquals("AGILE_MASTER", rootM.get("roleKey").asText());
        assertTrue(rootM.get("leadership").asBoolean(), "o criador da equipe é a liderança dela");
        assertEquals("MANUAL", jdbc.queryForObject("SELECT source FROM project_member_roles WHERE project_id = 'TIMEB'", String.class));

        // E convida; ana aceita o convite e entra como membro (sem liderança).
        JsonNode invite = mapper.readTree(mockMvc.perform(post("/api/squads/TIMEB/invites").header("Authorization", root)
                .contentType(MediaType.APPLICATION_JSON).content("{\"roleName\":\"Developer\"}"))
                .andReturn().getResponse().getContentAsString());
        assertEquals(200, status(post("/api/invites/" + invite.get("token").asText() + "/accept").header("Authorization", auth)));

        JsonNode m = memberships(auth);
        assertEquals("TIMEB", m.get("memberships").get(0).get("projectId").asText());
        assertFalse(m.get("memberships").get(0).get("leadership").asBoolean());
        assertEquals("TIMEB", m.get("activeProjectId").asText());

        // Telas da squad pelo vínculo: leitura sim; gestão não (a squad tem liderança), e sem o aviso de squad sem liderança.
        assertEquals(204, status(get("/api/squads/TIMEB").header("Authorization", auth)), "lê; squad ainda sem sincronização = 204");
        assertEquals(200, status(get("/api/squads/TIMEB/members").header("Authorization", auth)));
        assertEquals(200, status(get("/api/squads/TIMEB/panels").header("Authorization", auth)));
        assertEquals(200, status(get("/api/home").header("Authorization", auth)));
        assertEquals(403, status(get("/api/squads/TIMEB/member-metrics").header("Authorization", auth)));
        assertEquals(200, status(get("/api/squads/TIMEB/member-metrics").header("Authorization", root)));
        assertTrue(logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .noneMatch(l -> l.startsWith("authz_squad_without_leadership squad=TIMEB")), "a squad nasceu com liderança cadastrada");
        assertEquals("TIMEB", login("ana").get("accessibleProjects").get(0).get("projectId").asText());

        // Quem chega sem convite continua de fora.
        String bia = bearer(register("bia"));
        assertEquals(403, status(get("/api/squads/TIMEB").header("Authorization", bia)));
        assertEquals(403, status(post("/api/squads/TIMEB/invites").header("Authorization", bia).contentType(MediaType.APPLICATION_JSON).content("{\"roleName\":\"Developer\"}")));
    }

    @Test
    void primeiroAdmin_criaAEquipePeloProjeto_eNaoPrecisaDeRosterNemSquadIdNemCargo() throws Exception {
        register("root");
        // README: o primeiro ADMIN é promovido por SQL (não há bootstrap de admin no código). O token novo traz o papel.
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE email = 'root@totvs.com.br'");
        String auth = bearer(login("root"));

        assertEquals(200, status(post("/api/projects").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"TIMEB\",\"name\":\"Time B\"}")));

        JsonNode m = memberships(auth);
        assertEquals("TIMEB", m.get("memberships").get(0).get("projectId").asText());
        assertTrue(m.get("memberships").get(0).get("leadership").asBoolean(), "createManualProject dá liderança ao criador");
        assertEquals(200, status(get("/api/squads/TIMEB/member-metrics").header("Authorization", auth)));
        assertEquals(201, status(post("/api/squads/TIMEB/invites").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleName\":\"Developer\"}")));
        // Um ADMIN não cria vínculo pelo bootstrap em squad que ele só lê.
        assertEquals(200, status(get("/api/squads/OUTRA/members").header("Authorization", auth)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM project_member_roles WHERE project_id = 'OUTRA'", Integer.class));
    }
}
