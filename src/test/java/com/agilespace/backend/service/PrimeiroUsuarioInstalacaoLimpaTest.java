package com.agilespace.backend.service;

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
import org.springframework.test.web.servlet.RequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * O caminho do PRIMEIRO usuário numa instalação limpa (banco vazio) com {@code app.membership.mode=new} e
 * {@code app.membership.authz=new}, ponta a ponta por HTTP + Postgres real: cadastro, sessão, tentativa de criar a
 * equipe, bootstrap, telas da squad, convite. Fixa também o que NÃO funciona sem ADMIN (criar equipe e entrar por
 * "join"), para que uma mudança desse comportamento apareça como falha de teste. Sem Docker é ignorado.
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

    @BeforeEach
    void instalacaoLimpa() {
        jdbc.execute("TRUNCATE project_member_roles, squad_members, squad_member_exclusions, squads, projects, invites, link_requests, users CASCADE");
        cache.evictAll();
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
    void membroSemAdmin_naoCriaEquipePeloProjeto_masEntraPeloBootstrapEPassaEmTodasAsTelas() throws Exception {
        JsonNode ana = register("ana");
        String auth = bearer(ana);

        // Conta nova e limpa: sem vínculo, sem rastro (vai para o onboarding, não é "só legado").
        assertEquals(0, ana.get("accessibleProjects").size());
        assertTrue(ana.path("legacyOnly").isMissingNode() || ana.get("legacyOnly").isNull());
        assertEquals(0, memberships(auth).get("memberships").size());
        assertFalse(memberships(auth).get("legacyOnly").asBoolean());

        // Onboarding: nada para reconhecer; criar equipe pelo projeto exige AM/PL/ADMIN.
        assertEquals(200, status(get("/api/onboarding/suggestions").header("Authorization", auth)));
        assertEquals(403, status(post("/api/projects").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"TIMEA\",\"name\":\"Time A\"}")), "membro comum não cria equipe");

        // Primeira escrita numa squad vazia: bootstrap (cria o vínculo de liderança).
        assertEquals(200, status(post("/api/squads/TIMEA").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ceremonyMode\":\"manual\"}")));

        JsonNode m = memberships(auth);
        assertEquals("TIMEA", m.get("memberships").get(0).get("projectId").asText());
        assertTrue(m.get("memberships").get(0).get("leadership").asBoolean());
        assertEquals("TIMEA", m.get("activeProjectId").asText());

        // Telas da squad (leitura, escrita, gestão, listagem, home, convite) pelo vínculo.
        assertEquals(200, status(get("/api/squads/TIMEA").header("Authorization", auth)));
        assertEquals(200, status(get("/api/squads/TIMEA/members").header("Authorization", auth)));
        assertEquals(200, status(get("/api/squads/TIMEA/member-metrics").header("Authorization", auth)));
        assertEquals(200, status(get("/api/squads/TIMEA/panels").header("Authorization", auth)));
        assertEquals(200, status(post("/api/squads/TIMEA").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"syncJql\":\"project = TIMEA\"}")), "campo de liderança: ela é liderança agora");
        assertEquals(200, status(get("/api/home").header("Authorization", auth)));
        assertEquals(201, status(post("/api/squads/TIMEA/invites").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleName\":\"Developer\"}")));
        JsonNode squads = mapper.readTree(mockMvc.perform(get("/api/squads").header("Authorization", auth)).andReturn().getResponse().getContentAsString());
        assertEquals(1, squads.size());

        // Novo login: a sessão traz a squad dos vínculos.
        JsonNode again = login("ana");
        assertEquals("TIMEA", again.get("activeProjectId").asText());
        assertEquals("TIMEA", again.get("accessibleProjects").get(0).get("projectId").asText());

        // Quem chega depois, sem vínculo, não entra na squad (nem pelo bootstrap nem pelas telas).
        String bia = bearer(register("bia"));
        assertEquals(403, status(get("/api/squads/TIMEA").header("Authorization", bia)));
        assertEquals(403, status(post("/api/squads/TIMEA").header("Authorization", bia).contentType(MediaType.APPLICATION_JSON).content("{\"ceremonyMode\":\"manual\"}")));
        assertEquals(403, status(post("/api/squads/TIMEA/invites").header("Authorization", bia).contentType(MediaType.APPLICATION_JSON).content("{\"roleName\":\"Developer\"}")));
        assertEquals(0, memberships(bia).get("memberships").size());
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
        assertFalse(m.get("memberships").get(0).get("leadership").asBoolean(), "admin puro entra sem liderança (createManualProject)");
        assertEquals(200, status(get("/api/squads/TIMEB/member-metrics").header("Authorization", auth)));
        assertEquals(201, status(post("/api/squads/TIMEB/invites").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleName\":\"Developer\"}")));
        // Um ADMIN não cria vínculo pelo bootstrap em squad que ele só lê.
        assertEquals(200, status(get("/api/squads/OUTRA/members").header("Authorization", auth)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM project_member_roles WHERE project_id = 'OUTRA'", Integer.class));
    }
}
