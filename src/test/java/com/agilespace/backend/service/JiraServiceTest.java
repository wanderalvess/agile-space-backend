package com.agilespace.backend.service;

import com.agilespace.backend.dto.JiraSearchRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

public class JiraServiceTest {

    private JiraService service;
    private MockRestServiceServer server;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    public void setup() {
        service = new JiraService();
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        server = MockRestServiceServer.createServer(restTemplate);
    }

    private JiraSearchRequest searchRequest(String jql) {
        JiraSearchRequest request = new JiraSearchRequest();
        request.setDomain("https://jira.empresa.com.br");
        request.setToken("  token-secreto  ");
        request.setJql(jql);
        return request;
    }

    // ---------- searchIssues ----------

    @Test
    public void testSearchIssuesStripsProtocolAndSendsBearerToken() {
        server.expect(requestTo(containsString("https://jira.empresa.com.br/rest/api/2/search")))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token-secreto"))
                .andRespond(withSuccess("{\"issues\":[]}", MediaType.APPLICATION_JSON));

        ResponseEntity<String> response = service.searchIssues(searchRequest("project = DDW"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        server.verify();
    }

    @Test
    public void testSearchIssuesEncodesJqlOnceWithoutDoubleEncoding() {
        server.expect(requestTo(containsString("jql=project+%3D+DDW+AND+status+%3D+Done")))
                .andExpect(requestTo(not(containsString("%253D"))))
                .andRespond(withSuccess("{\"issues\":[]}", MediaType.APPLICATION_JSON));

        service.searchIssues(searchRequest("  project = DDW AND status = Done  "));

        server.verify();
    }

    @Test
    public void testSearchIssuesClampsMaxResultsAndStartAt() {
        server.expect(requestTo(containsString("maxResults=100")))
                .andExpect(requestTo(containsString("startAt=0")))
                .andRespond(withSuccess("{\"issues\":[]}", MediaType.APPLICATION_JSON));

        JiraSearchRequest request = searchRequest("project = DDW");
        request.setMaxResults(5000);
        request.setStartAt(-10);
        service.searchIssues(request);

        server.verify();
    }

    @Test
    public void testSearchIssuesUsesMinimumOfOneResult() {
        server.expect(requestTo(containsString("maxResults=1")))
                .andRespond(withSuccess("{\"issues\":[]}", MediaType.APPLICATION_JSON));

        JiraSearchRequest request = searchRequest("project = DDW");
        request.setMaxResults(0);
        service.searchIssues(request);

        server.verify();
    }

    @Test
    public void testSearchIssuesHonoursCustomFieldList() {
        server.expect(requestTo(containsString("fields=summary%2Cstatus")))
                .andRespond(withSuccess("{\"issues\":[]}", MediaType.APPLICATION_JSON));

        JiraSearchRequest request = searchRequest("project = DDW");
        request.setFields(List.of("summary", "status"));
        service.searchIssues(request);

        server.verify();
    }

    @Test
    public void testSearchIssuesFetchesCompleteWorklogWhenTruncated() throws Exception {
        String searchBody = "{\"issues\":[{\"key\":\"DDW-1\",\"fields\":{\"worklog\":{\"total\":3,"
                + "\"worklogs\":[{\"id\":\"1\"}]}}}]}";
        server.expect(requestTo(containsString("/rest/api/2/search")))
                .andRespond(withSuccess(searchBody, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://jira.empresa.com.br/rest/api/2/issue/DDW-1/worklog"))
                .andExpect(header("Authorization", "Bearer token-secreto"))
                .andRespond(withSuccess("{\"worklogs\":[{\"id\":\"1\"},{\"id\":\"2\"},{\"id\":\"3\"}]}",
                        MediaType.APPLICATION_JSON));

        ResponseEntity<String> response = service.searchIssues(searchRequest("project = DDW"));

        JsonNode worklogs = mapper.readTree(response.getBody())
                .get("issues").get(0).get("fields").get("worklog").get("worklogs");
        assertEquals(3, worklogs.size());
        server.verify();
    }

    @Test
    public void testSearchIssuesSkipsWorklogFetchWhenComplete() {
        String searchBody = "{\"issues\":[{\"key\":\"DDW-1\",\"fields\":{\"worklog\":{\"total\":1,"
                + "\"worklogs\":[{\"id\":\"1\"}]}}}]}";
        server.expect(requestTo(containsString("/rest/api/2/search")))
                .andRespond(withSuccess(searchBody, MediaType.APPLICATION_JSON));

        assertEquals(HttpStatus.OK, service.searchIssues(searchRequest("project = DDW")).getStatusCode());
        // Nenhuma chamada extra de worklog foi esperada: verify falharia se tivesse ocorrido.
        server.verify();
    }

    @Test
    public void testSearchIssuesKeepsPartialWorklogWhenCompletionCallFails() throws Exception {
        String searchBody = "{\"issues\":[{\"key\":\"DDW-1\",\"fields\":{\"worklog\":{\"total\":3,"
                + "\"worklogs\":[{\"id\":\"1\"}]}}}]}";
        server.expect(requestTo(containsString("/rest/api/2/search")))
                .andRespond(withSuccess(searchBody, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/worklog")))
                .andRespond(withServerError());

        ResponseEntity<String> response = service.searchIssues(searchRequest("project = DDW"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode worklogs = mapper.readTree(response.getBody())
                .get("issues").get(0).get("fields").get("worklog").get("worklogs");
        assertEquals(1, worklogs.size());
    }

    @Test
    public void testSearchIssuesPropagatesJiraStatusAndBodyOnError() {
        server.expect(requestTo(containsString("/rest/api/2/search")))
                .andRespond(withUnauthorizedRequest().body("{\"errorMessages\":[\"Token invalido\"]}"));

        ResponseEntity<String> response = service.searchIssues(searchRequest("project = DDW"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertThat(response.getBody(), containsString("Token invalido"));
    }

    @Test
    public void testSearchIssuesReturnsInternalErrorOnTransportFailure() {
        server.expect(requestTo(containsString("/rest/api/2/search")))
                .andRespond(request -> {
                    throw new java.io.IOException("conexao recusada");
                });

        ResponseEntity<String> response = service.searchIssues(searchRequest("project = DDW"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertThat(response.getBody(), containsString("Erro ao conectar ao Jira"));
    }

    // ---------- getMyself ----------

    @Test
    public void testGetMyselfCallsMyselfEndpoint() {
        server.expect(requestTo("https://jira.empresa.com.br/rest/api/2/myself"))
                .andExpect(header("Authorization", "Bearer token-secreto"))
                .andRespond(withSuccess("{\"name\":\"joao\"}", MediaType.APPLICATION_JSON));

        ResponseEntity<String> response = service.getMyself("  http://jira.empresa.com.br ", "  token-secreto  ");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertThat(response.getBody(), containsString("joao"));
        server.verify();
    }

    @Test
    public void testGetMyselfPropagatesUnauthorized() {
        server.expect(requestTo(containsString("/myself")))
                .andRespond(withUnauthorizedRequest().body("{\"errorMessages\":[\"nao autenticado\"]}"));

        ResponseEntity<String> response = service.getMyself("jira.empresa.com.br", "token-invalido");

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    // ---------- getGreenhopperWorkData ----------

    @Test
    public void testGreenhopperRequiresRapidViewId() {
        ResponseEntity<String> response = service.getGreenhopperWorkData("jira.empresa.com.br", "token", null, "DDW");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertThat(response.getBody(), containsString("rapidViewId"));
        server.verify();
    }

    @Test
    public void testGreenhopperBuildsUrlWithRapidViewAndProjectKey() {
        server.expect(requestTo("https://jira.empresa.com.br/rest/greenhopper/1.0/xboard/work/allData.json"
                + "?rapidViewId=42&selectedProjectKey=DDW+MISSI"))
                .andRespond(withSuccess("{\"sprintsData\":{}}", MediaType.APPLICATION_JSON));

        ResponseEntity<String> response = service.getGreenhopperWorkData(
                "https://jira.empresa.com.br", "token", 42L, "  DDW MISSI  ");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        server.verify();
    }

    @Test
    public void testGreenhopperOmitsProjectKeyWhenBlank() {
        server.expect(requestTo("https://jira.empresa.com.br/rest/greenhopper/1.0/xboard/work/allData.json"
                + "?rapidViewId=42"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertEquals(HttpStatus.OK,
                service.getGreenhopperWorkData("jira.empresa.com.br", "token", 42L, "   ").getStatusCode());
        server.verify();
    }

    @Test
    public void testGreenhopperPropagatesJiraError() {
        server.expect(requestTo(containsString("allData.json")))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("{\"errorMessages\":[\"sem permissao\"]}"));

        ResponseEntity<String> response = service.getGreenhopperWorkData("jira.empresa.com.br", "token", 42L, null);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertThat(response.getBody(), containsString("sem permissao"));
    }
}
