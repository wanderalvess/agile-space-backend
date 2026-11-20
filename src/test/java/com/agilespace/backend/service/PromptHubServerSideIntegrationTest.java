package com.agilespace.backend.service;

import com.agilespace.backend.security.JwtTokenUtil;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Biblioteca de IA contra um Postgres real: migração V91 + validação do schema, busca/filtros/ordenação/
 * paginação no servidor (Criteria + translate), visibilidade por perfil, favoritos, facets, versões e uso por
 * semana. Sem Docker o teste é ignorado, como os demais Testcontainers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PromptHubServerSideIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenUtil jwt;
    @Autowired private ObjectMapper mapper;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE prompt_hub, prompt_collections, users CASCADE");
        user("ana", "MEMBER");
        user("bia", "MEMBER");
        user("adm", "ADMIN");
    }

    private void user(String id, String role) {
        jdbc.update("INSERT INTO users (id, email, name, role, is_active, is_guest, password_hash) VALUES (?, ?, ?, ?, TRUE, FALSE, 'hash')",
                id, id + "@totvs.com.br", "Pessoa " + id, role);
    }

    private String token(String id) {
        String role = "adm".equals(id) ? "ADMIN" : "MEMBER";
        return "Bearer " + jwt.generateToken(id, id + "@totvs.com.br", "Pessoa " + id, role, null, null, null);
    }

    private JsonNode call(MockHttpServletRequestBuilder b, String as, int expected) throws Exception {
        if (as != null) b.header("Authorization", token(as));
        MvcResult r = mvc.perform(b).andExpect(status().is(expected)).andReturn();
        String body = r.getResponse().getContentAsString();
        return body.isEmpty() ? null : mapper.readTree(body);
    }

    private String create(String as, String title, String type, String visibility, String status, String content, String... tags) throws Exception {
        String tagJson = mapper.writeValueAsString(tags);
        String body = "{\"title\":" + mapper.writeValueAsString(title) + ",\"type\":\"" + type + "\",\"visibility\":\"" + visibility
                + "\",\"status\":\"" + status + "\",\"content\":" + mapper.writeValueAsString(content) + ",\"tags\":" + tagJson + "}";
        return call(post("/api/prompts").contentType(MediaType.APPLICATION_JSON).content(body), as, 201).get("id").asText();
    }

    private List<String> titles(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("content").forEach(n -> out.add(n.get("title").asText()));
        return out;
    }

    private JsonNode list(String as, String query) throws Exception {
        return call(get("/api/prompts?" + query), as, 200);
    }

    @Test
    void searchFiltersVisibilitySortAndPagination() throws Exception {
        create("ana", "Ação de retro", "prompt", "public", "published", "texto sobre coração", "scrum", "retro");
        create("ana", "Skill privada", "skill", "private", "published", "segredo", "scrum");
        create("bia", "Arquivado", "prompt", "public", "arquivado", "velho");
        create("bia", "Archived en", "prompt", "public", "archived", "old");
        create("bia", "Zeta pública", "agent", "public", "published", "z", "kanban");

        // bia não vê o privado da ana; não vê arquivado por padrão? (autor vê o próprio arquivado só com status=all)
        assertEquals(List.of("Zeta pública", "Ação de retro"), titles(list("bia", "sort=recent")).stream().filter(t -> !t.equals("Arquivado")).toList());
        assertFalse(titles(list("bia", "sort=recent")).contains("Skill privada"));
        assertFalse(titles(list("bia", "sort=recent")).contains("Arquivado"));
        assertTrue(titles(list("bia", "sort=recent&status=all")).contains("Arquivado"));
        assertEquals(List.of("Archived en", "Arquivado"), titles(list("bia", "status=arquivado&sort=az")));
        assertEquals(List.of("Archived en", "Arquivado"), titles(list("bia", "status=archived&sort=az")));
        assertFalse(titles(list("bia", "sort=recent")).contains("Archived en"));

        // visibilidade
        assertEquals(List.of("Skill privada"), titles(list("ana", "visibility=private")));
        assertEquals(List.of(), titles(list("bia", "visibility=private")));
        assertTrue(titles(list("ana", "visibility=public&sort=az")).containsAll(List.of("Ação de retro", "Zeta pública")));
        assertEquals(5, list("adm", "sort=recent&status=all").get("totalElements").asInt());

        // busca sem acento / sem caixa, em título, conteúdo, tag e autor
        assertEquals(List.of("Ação de retro"), titles(list("bia", "query=acao&sort=recent")));
        assertEquals(List.of("Ação de retro"), titles(list("bia", "query=CORACAO&sort=recent")));
        assertEquals(List.of("Zeta pública"), titles(list("bia", "query=kanban&sort=recent")));
        assertEquals(2, list("ana", "query=scrum&sort=recent").get("totalElements").asInt());
        assertEquals(List.of("Skill privada"), titles(list("ana", "query=pessoa ana segredo&sort=recent&type=skill")));

        // filtros, ordenação e paginação
        assertEquals(List.of("Zeta pública"), titles(list("bia", "type=agent")));
        assertEquals(List.of("Ação de retro"), titles(list("bia", "tag=Retro")));
        assertEquals(List.of("Ação de retro", "Skill privada", "Zeta pública"), titles(list("ana", "sort=az")));
        JsonNode p0 = list("ana", "sort=az&size=2&page=0");
        assertEquals(3, p0.get("totalElements").asInt());
        assertEquals(2, p0.get("totalPages").asInt());
        assertEquals(2, p0.get("size").asInt());
        assertEquals(List.of("Zeta pública"), titles(list("ana", "sort=az&size=2&page=1")));
        assertEquals(24, list("ana", "sort=recent").get("size").asInt());

        // canEdit / canModerate
        JsonNode adminView = list("adm", "sort=az");
        assertTrue(adminView.get("content").get(0).get("canModerate").asBoolean());
        JsonNode biaView = list("bia", "sort=az");
        assertFalse(biaView.get("content").get(0).get("canEdit").asBoolean());
    }

    @Test
    void legacyListWithoutNewParamsKeepsOldBehaviour() throws Exception {
        create("ana", "Pública", "prompt", "public", "published", "x");
        create("ana", "Privada", "prompt", "private", "published", "y");
        create("ana", "Velha", "prompt", "public", "archived", "z");

        JsonNode legacy = list("bia", "page=0&size=50");
        assertEquals(List.of("Pública", "Velha"), titles(legacy).stream().sorted().toList());
        assertTrue(legacy.has("totalPages") && legacy.has("number"), "formato Page do Spring preservado");
        assertEquals(3, list("ana", "authorId=ana&page=0&size=50").get("totalElements").asInt());
    }

    @Test
    void favoritesFacetsVersionsAndUsage() throws Exception {
        String a = create("ana", "Primeiro", "prompt", "public", "published", "v1", "scrum");
        String priv = create("ana", "Privado", "skill", "private", "published", "p");
        create("ana", "Outro", "skill", "public", "published", "o", "scrum", "kanban");

        // favoritos: idempotente, 404 em item privado de outro, filtro favorites
        assertTrue(call(post("/api/prompts/" + a + "/favorite"), "bia", 200).get("favorite").asBoolean());
        assertTrue(call(post("/api/prompts/" + a + "/favorite"), "bia", 200).get("favorite").asBoolean());
        call(post("/api/prompts/" + priv + "/favorite"), "bia", 404);
        assertEquals(List.of("Primeiro"), titles(list("bia", "favorites=true")));
        assertTrue(list("bia", "sort=az").get("content").get(1).get("favorite").asBoolean(), "Primeiro é o 2o em A-Z");
        assertFalse(call(delete("/api/prompts/" + a + "/favorite"), "bia", 200).get("favorite").asBoolean());
        assertFalse(call(delete("/api/prompts/" + a + "/favorite"), "bia", 200).get("favorite").asBoolean());
        assertEquals(List.of(), titles(list("bia", "favorites=true")));
        call(post("/api/prompts/" + a + "/favorite"), null, 401);

        // facets
        JsonNode f = call(get("/api/prompts/facets?query=&status="), "bia", 200);
        assertEquals(2, f.get("total").asInt());
        assertEquals(1, f.get("typeCounts").get("prompt").asInt());
        assertEquals(1, f.get("typeCounts").get("skill").asInt());
        assertEquals("scrum", f.get("topTags").get(0).get("tag").asText());
        assertEquals(2, f.get("topTags").get(0).get("count").asInt());
        assertEquals(2, f.get("totals").get("items").asInt());
        assertEquals(3, call(get("/api/prompts/facets"), "ana", 200).get("totals").get("items").asInt());

        // versões: criação = v1; editar conteúdo = v2; só impacto não versiona; restaurar = v3
        call(put("/api/prompts/" + a).contentType(MediaType.APPLICATION_JSON).content(
                "{\"title\":\"Primeiro\",\"type\":\"prompt\",\"visibility\":\"public\",\"status\":\"published\",\"content\":\"v2\",\"tags\":[\"scrum\"]}"), "ana", 200);
        call(put("/api/prompts/" + a).contentType(MediaType.APPLICATION_JSON).content(
                "{\"title\":\"Primeiro\",\"type\":\"prompt\",\"visibility\":\"public\",\"status\":\"published\",\"content\":\"v2\",\"impact\":\"high\",\"tags\":[\"scrum\"]}"), "ana", 200);
        JsonNode versions = call(get("/api/prompts/" + a + "/versions"), "bia", 200);
        assertEquals(2, versions.size());
        assertEquals(2, versions.get(0).get("versionNumber").asInt());
        assertTrue(versions.get(0).get("current").asBoolean());
        assertEquals("v1", call(get("/api/prompts/" + a + "/versions/1"), "bia", 200).get("content").asText());
        call(get("/api/prompts/" + a + "/versions/9"), "bia", 404);
        call(post("/api/prompts/" + a + "/versions/1/restore"), "bia", 403);
        JsonNode restored = call(post("/api/prompts/" + a + "/versions/1/restore"), "ana", 200);
        assertEquals("v1", restored.get("content").asText());
        assertEquals(3, call(get("/api/prompts/" + a + "/versions"), "ana", 200).size());
        call(get("/api/prompts/" + priv + "/versions"), "bia", 404);
        assertEquals(1, call(get("/api/prompts/" + priv + "/versions"), "adm", 200).size());

        // uso por semana
        call(post("/api/prompts/" + a + "/use"), "bia", 200);
        call(post("/api/prompts/" + a + "/use"), "bia", 200);
        call(post("/api/prompts/" + a + "/clone").contentType(MediaType.APPLICATION_JSON).content("{}"), "bia", 201);
        JsonNode usage = call(get("/api/prompts/" + a + "/usage?weeks=4"), "bia", 200);
        assertEquals(4, usage.size());
        assertEquals(2, usage.get(3).get("uses").asInt());
        assertEquals(1, usage.get(3).get("clones").asInt());
        assertEquals(0, usage.get(0).get("uses").asInt());
        call(get("/api/prompts/" + priv + "/usage"), "bia", 404);

        // excluir o item leva favoritos, versões e eventos (FK em cascata)
        call(delete("/api/prompts/" + a), "ana", 204);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM prompt_versions WHERE prompt_id = ?::uuid", Integer.class, a));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM prompt_usage_events WHERE prompt_id = ?::uuid", Integer.class, a));
    }

    @Test
    void publicRouteMirrorsContract() throws Exception {
        create("ana", "Ação pública", "prompt", "public", "published", "x", "scrum");
        create("ana", "Privada", "prompt", "private", "published", "y");

        JsonNode page = call(get("/api/public/prompt-hub/items?query=acao&sort=recent"), null, 200);
        assertEquals(List.of("Ação pública"), titles(page));
        assertFalse(page.get("content").get(0).has("authorId"));
        JsonNode facets = call(get("/api/public/prompt-hub/facets"), null, 200);
        assertEquals(1, facets.get("total").asInt());
    }

    @Test
    void adminModeratesCommentsAndAnyItem() throws Exception {
        String a = create("ana", "Do Ana", "prompt", "public", "published", "x");
        JsonNode c = call(post("/api/prompts/" + a + "/comments").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"oi\"}"), "bia", 201);
        call(delete("/api/prompts/" + a + "/comments/" + c.get("id").asText()), "adm", 204);
        JsonNode c2 = call(post("/api/prompts/" + a + "/comments").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"oi\"}"), "bia", 201);
        call(delete("/api/prompts/" + a + "/comments/" + c2.get("id").asText()), "ana", 204);
        call(put("/api/prompts/" + a).contentType(MediaType.APPLICATION_JSON).content(
                "{\"title\":\"Editado\",\"type\":\"prompt\",\"visibility\":\"public\",\"content\":\"x\"}"), "adm", 200);
        call(delete("/api/prompts/" + a), "adm", 204);
    }
}
