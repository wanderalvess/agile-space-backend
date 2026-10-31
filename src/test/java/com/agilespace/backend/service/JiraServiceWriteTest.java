package com.agilespace.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@DisplayName("JiraService - escrita (renomear, criar sub-tarefa, comentar)")
class JiraServiceWriteTest {

    private JiraService service;
    private MockRestServiceServer server;

    @BeforeEach
    void setup() {
        service = new JiraService();
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "strictRestTemplate");
        server = MockRestServiceServer.createServer(restTemplate);
    }

    @Test
    void renomearEnviaSoOSummaryViaPut() {
        server.expect(requestTo("https://jira.empresa.com.br/rest/api/2/issue/DDW-1"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("Authorization", "Bearer tok"))
                .andExpect(header("User-Agent", JiraService.BROWSER_USER_AGENT))
                .andExpect(header("X-Atlassian-Token", "no-check"))
                .andExpect(header("Referer", "https://jira.empresa.com.br/"))
                .andExpect(jsonPath("$.fields.summary").value("Novo (DEV 10h / QA 2h)"))
                .andExpect(jsonPath("$.fields.length()").value(1))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        service.updateIssueSummary("https://jira.empresa.com.br/", "tok", "DDW-1", "Novo (DEV 10h / QA 2h)");
        server.verify();
    }

    @Test
    void criarSubTarefaMandaPaiTipoTituloEEstimativaEDevolveAChave() {
        server.expect(requestTo("https://jira.empresa.com.br/rest/api/2/issue"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.fields.project.key").value("DDW"))
                .andExpect(jsonPath("$.fields.parent.key").value("DDW-1"))
                .andExpect(jsonPath("$.fields.issuetype.name").value("Codificação"))
                .andExpect(jsonPath("$.fields.summary").value("DDW-1 - Codificação - X"))
                .andExpect(jsonPath("$.fields.timetracking.originalEstimate").value("1h 30m"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"10\",\"key\":\"DDW-2\"}"));

        String key = service.createSubtask("jira.empresa.com.br", "tok", "DDW", "DDW-1", "Codificação", "DDW-1 - Codificação - X", 1.5);

        assertEquals("DDW-2", key);
        server.verify();
    }

    @Test
    void criarSubTarefaSemHorasNaoMandaTimetracking() {
        server.expect(requestTo("https://jira.empresa.com.br/rest/api/2/issue"))
                .andExpect(jsonPath("$.fields.timetracking").doesNotExist())
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{\"key\":\"DDW-3\"}"));

        assertEquals("DDW-3", service.createSubtask("jira.empresa.com.br", "tok", "DDW", "DDW-1", "Code Review", "T", null));
        server.verify();
    }

    @Test
    void comentarPostaNoEndpointDeComentarioDaIssue() {
        server.expect(requestTo("https://jira.empresa.com.br/rest/api/2/issue/DDW-2/comment"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.body").value("DEV (10h): fazer x"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{\"id\":\"555\"}"));

        assertEquals("555", service.addComment("jira.empresa.com.br", "tok", "DDW-2", "DEV (10h): fazer x"));
        server.verify();
    }

    @Test
    void erroDoJiraViraExcecaoComStatusEMotivoLegivelSemToken() {
        server.expect(requestTo(containsString("/rest/api/2/issue")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"errorMessages\":[],\"errors\":{\"issuetype\":\"tipo de issue inválido\"}}"));

        JiraService.JiraWriteException ex = assertThrows(JiraService.JiraWriteException.class,
                () -> service.createSubtask("jira.empresa.com.br", "tok-secreto", "DDW", "DDW-1", "Inexistente", "T", null));

        assertEquals(400, ex.getStatus());
        assertTrue(ex.getMessage().contains("issuetype: tipo de issue inválido"));
        assertFalse(ex.getMessage().contains("tok-secreto"));
    }

    @Test
    void status401ViraMensagemDeTokenInvalido() {
        server.expect(requestTo(containsString("/comment")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        JiraService.JiraWriteException ex = assertThrows(JiraService.JiraWriteException.class,
                () -> service.addComment("jira.empresa.com.br", "tok", "DDW-2", "x"));
        assertEquals(401, ex.getStatus());
        assertTrue(ex.getMessage().contains("token do Jira inválido"));
    }

    @Test
    void chaveDeIssueInvalidaERecusadaAntesDeQualquerChamada() {
        JiraService.JiraWriteException ex = assertThrows(JiraService.JiraWriteException.class,
                () -> service.addComment("jira.empresa.com.br", "tok", "../admin", "x"));
        assertEquals(400, ex.getStatus());
        server.verify(); // nenhuma requisição esperada nem feita
    }

    @Test
    void redirecionamentoEmEscritaNaoESeguido() {
        server.expect(requestTo("https://jira.empresa.com.br/rest/api/2/issue/DDW-1"))
                .andRespond(withStatus(HttpStatus.FOUND).header("Location", "https://outro.host/x"));

        JiraService.JiraWriteException ex = assertThrows(JiraService.JiraWriteException.class,
                () -> service.updateIssueSummary("jira.empresa.com.br", "tok", "DDW-1", "Novo"));
        assertEquals(302, ex.getStatus());
        server.verify();
    }

    @Test
    void listaSubTarefasDaPaiComJqlParent() {
        server.expect(requestTo(containsString("/rest/api/2/search?jql=parent+%3D+DDW-1")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"issues\":[{\"key\":\"DDW-2\",\"fields\":{\"summary\":\"S\",\"issuetype\":{\"name\":\"Codificação\"}}}]}", MediaType.APPLICATION_JSON));

        List<JsonNode> subs = service.listSubtasks("jira.empresa.com.br", "tok", "DDW-1");
        assertEquals(1, subs.size());
        assertEquals("DDW-2", subs.get(0).get("key").asText());
    }

    @Test
    void listaTiposDoProjetoViaCreatemeta() {
        server.expect(requestTo("https://jira.empresa.com.br/rest/api/2/issue/createmeta?projectKeys=DDW"))
                .andRespond(withSuccess("{\"projects\":[{\"issuetypes\":[{\"id\":\"5\",\"name\":\"Codificação\",\"subtask\":true},{\"id\":\"1\",\"name\":\"História\",\"subtask\":false}]}]}", MediaType.APPLICATION_JSON));

        List<JsonNode> types = service.listProjectIssueTypes("jira.empresa.com.br", "tok", "DDW");
        assertEquals(2, types.size());
        assertTrue(types.get(0).get("subtask").asBoolean());
    }

    @Test
    void editmetaComSummaryLiberaRenomear() {
        server.expect(requestTo("https://jira.empresa.com.br/rest/api/2/issue/DDW-1/editmeta"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"fields\":{\"summary\":{\"name\":\"Summary\"}}}", MediaType.APPLICATION_JSON));

        assertDoesNotThrow(() -> service.assertSummaryEditable("jira.empresa.com.br", "tok", "DDW-1"));
    }

    @Test
    void editmetaSemSummaryDa403ComMotivoClaro() {
        server.expect(requestTo("https://jira.empresa.com.br/rest/api/2/issue/DDW-1/editmeta"))
                .andRespond(withSuccess("{\"fields\":{\"description\":{}}}", MediaType.APPLICATION_JSON));

        JiraService.JiraWriteException ex = assertThrows(JiraService.JiraWriteException.class,
                () -> service.assertSummaryEditable("jira.empresa.com.br", "tok", "DDW-1"));
        assertEquals(403, ex.getStatus());
        assertTrue(ex.getMessage().contains("editar o título"));
    }

    @Test
    void escritaRecusadaComHtmlExplicaProxyOuXsrf() {
        server.expect(requestTo("https://jira.empresa.com.br/rest/api/2/issue/DDW-1"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.TEXT_HTML)
                        .body("<html><head><title>XSRF check failed</title></head><body>…</body></html>"));

        JiraService.JiraWriteException ex = assertThrows(JiraService.JiraWriteException.class,
                () -> service.updateIssueSummary("jira.empresa.com.br", "tok", "DDW-1", "Novo"));
        assertEquals(403, ex.getStatus());
        assertTrue(ex.getMessage().contains("página HTML"));
        assertTrue(ex.getMessage().contains("XSRF check failed"));
    }

    @Test
    void listaComentariosDaIssueSoLendo() {
        server.expect(requestTo("https://jira.empresa.com.br/rest/api/2/issue/DDW-1/comment?maxResults=100&orderBy=-created"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Referer", "https://jira.empresa.com.br/"))
                .andRespond(withSuccess("{\"comments\":[{\"id\":\"9\",\"body\":\"Apoio docker\"},{\"id\":\"8\",\"body\":\"antigo\"}]}", MediaType.APPLICATION_JSON));

        List<JsonNode> comments = service.listComments("jira.empresa.com.br", "tok", "DDW-1");
        assertEquals(2, comments.size());
        assertEquals("Apoio docker", comments.get(0).get("body").asText());
    }

    @Test
    void formataDuracaoDoJira() {
        assertEquals("10h", JiraService.formatJiraDuration(10));
        assertEquals("1h 30m", JiraService.formatJiraDuration(1.5));
        assertEquals("45m", JiraService.formatJiraDuration(0.75));
    }
}
