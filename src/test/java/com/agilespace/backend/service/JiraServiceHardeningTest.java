package com.agilespace.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Proxy do Jira: validação de domínio, alvos internos, redirecionamento e corpo de erro. */
class JiraServiceHardeningTest {

    private JiraService service;
    private MockRestServiceServer server;

    @BeforeEach
    void setup() {
        service = new JiraService();
        RestTemplate rt = (RestTemplate) ReflectionTestUtils.getField(service, "strictRestTemplate");
        server = MockRestServiceServer.createServer(rt);
    }

    @Test
    void cleanDomainAceitaHostComEsquemaEPorta() {
        assertEquals("jira.empresa.com.br", JiraService.cleanDomain("  https://jira.empresa.com.br/ "));
        assertEquals("jira.empresa.com.br:8443", JiraService.cleanDomain("jira.empresa.com.br:8443"));
    }

    @Test
    void cleanDomainRecusaCaminhoCredencialConsultaEVazio() {
        for (String bad : new String[] {"jira.com/rest", "u:p@jira.com", "jira.com?x=1", "jira.com#x", "", "   ", null, "jira com", "jira.com:99999x"}) {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> JiraService.cleanDomain(bad), String.valueOf(bad));
            assertEquals(400, ex.getStatusCode().value());
        }
    }

    @Test
    void metodosDoProxyRecusamDominioInvalidoComBadRequest() {
        assertThrows(ResponseStatusException.class, () -> service.getMyself("evil.com/x", "t"));
        assertThrows(ResponseStatusException.class, () -> service.getFields("a@evil.com", "t"));
    }

    @Test
    void loopbackLinkLocalEMetadataSaoSempreBloqueados() {
        for (String host : new String[] {"127.0.0.1", "169.254.169.254", "0.0.0.0", "localhost"}) {
            assertThrows(IllegalArgumentException.class, () -> service.assertNotBlockedHost(URI.create("https://" + host)), host);
        }
    }

    @Test
    void redesPrivadasSaoBloqueadasPorPadrao() {
        for (String host : new String[] {"10.0.0.5", "172.18.0.2", "192.168.1.10", "100.64.0.1"}) {
            assertThrows(IllegalArgumentException.class, () -> service.assertNotBlockedHost(URI.create("https://" + host)), host);
        }
    }

    @Test
    void redePrivadaPodeSerLiberadaPorConfiguracao() {
        ReflectionTestUtils.setField(service, "allowPrivateHosts", true);
        assertDoesNotThrow(() -> service.assertNotBlockedHost(URI.create("https://10.0.0.5")));
        // loopback continua bloqueado mesmo liberando redes privadas
        assertThrows(IllegalArgumentException.class, () -> service.assertNotBlockedHost(URI.create("https://127.0.0.1")));
    }

    @Test
    void classificaEnderecosIpv6EIpv4() throws Exception {
        assertTrue(JiraService.isPrivateNetwork(InetAddress.getByName("fd12:3456::1")));
        assertTrue(JiraService.isPrivateNetwork(InetAddress.getByName("100.127.255.254")));
        assertFalse(JiraService.isPrivateNetwork(InetAddress.getByName("100.128.0.1")));
        assertFalse(JiraService.isPrivateNetwork(InetAddress.getByName("8.8.8.8")));
    }

    @Test
    void segueRedirecionamentoSoNoMesmoHostEMantemOToken() {
        HttpHeaders loc = new HttpHeaders();
        loc.setLocation(URI.create("https://jira.empresa.com.br/rest/api/2/myself/"));
        server.expect(requestTo("https://jira.empresa.com.br/rest/api/2/myself"))
                .andRespond(withStatus(HttpStatus.MOVED_PERMANENTLY).headers(loc));
        server.expect(requestTo("https://jira.empresa.com.br/rest/api/2/myself/"))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess("{\"name\":\"x\"}", MediaType.APPLICATION_JSON));

        ResponseEntity<String> r = service.getMyself("jira.empresa.com.br", "tok");

        assertEquals(HttpStatus.OK, r.getStatusCode());
        server.verify();
    }

    @Test
    void recusaRedirecionamentoParaOutroHostSemEnviarOToken() {
        HttpHeaders loc = new HttpHeaders();
        loc.setLocation(URI.create("https://evil.example.com/steal"));
        server.expect(requestTo("https://jira.empresa.com.br/rest/api/2/myself"))
                .andRespond(withStatus(HttpStatus.FOUND).headers(loc));

        ResponseEntity<String> r = service.getMyself("jira.empresa.com.br", "tok");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, r.getStatusCode());
        assertFalse(r.getBody().contains("evil.example.com"), "corpo de erro não repete o destino");
        server.verify(); // só a primeira chamada aconteceu
    }

    @Test
    void recusaRedirecionamentoParaHttp() {
        HttpHeaders loc = new HttpHeaders();
        loc.setLocation(URI.create("http://jira.empresa.com.br/login"));
        server.expect(requestTo("https://jira.empresa.com.br/rest/api/2/myself"))
                .andRespond(withStatus(HttpStatus.FOUND).headers(loc));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, service.getMyself("jira.empresa.com.br", "tok").getStatusCode());
    }

    @Test
    void jsonErrorEscapaAspasEQuebrasDeLinha() throws Exception {
        String body = JiraService.jsonError("falha \"x\"\nlinha");
        assertEquals("falha \"x\"\nlinha", new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("error").asText());
    }

    @Test
    void describeNaoVazaUrlNemMensagemBruta() {
        Exception e = new org.springframework.web.client.ResourceAccessException(
                "I/O error on GET request for \"https://10.0.0.1:5432/x\": Connection refused",
                new java.net.ConnectException("Connection refused"));
        String msg = JiraService.describe(e);
        assertFalse(msg.contains("10.0.0.1"));
        assertEquals("não foi possível conectar ao Jira", msg);
    }
}
