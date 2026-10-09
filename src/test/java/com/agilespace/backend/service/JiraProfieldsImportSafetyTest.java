package com.agilespace.backend.service;

import com.agilespace.backend.domain.ProjectMemberRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Reimportação não apaga quem entrou fora do Jira; dedupe não perde AM/PL; mensagens de erro do Jira. */
class JiraProfieldsImportSafetyTest {

    private ProjectMemberRole row(String role, String key, boolean lead, String account, String email, String userId, String name) {
        return ProjectMemberRole.builder().projectId("P").roleName(role).roleKey(key).isLeadership(lead)
                .jiraAccountId(account).email(email).userId(userId).displayName(name).build();
    }

    @Test
    void reimportCopiaOVinculoDeContaParaALinhaNovaDaMesmaPessoa() {
        var old = row("Developer", "DEVELOPER", false, "ana", "ana@x.com", "u-ana", "Ana");
        var incoming = row("Developer", "DEVELOPER", false, "ana", "ANA@x.com", null, "Ana");

        var out = JiraProfieldsService.mergeWithExistingTeam(List.of(old), List.of(incoming), List.of(incoming));

        assertEquals(1, out.size());
        assertEquals("u-ana", out.get(0).getUserId());
    }

    @Test
    void reimportMantemQuemEntrouPorSouEuOuJoinEQueONaoEstaNoJira() {
        var joined = row("QA", "QA", false, null, "bia@x.com", "u-bia", "Bia");
        var jiraPerson = row("Developer", "DEVELOPER", false, "caio", "caio@x.com", null, "Caio");

        var out = JiraProfieldsService.mergeWithExistingTeam(List.of(joined), List.of(jiraPerson), List.of(jiraPerson));

        assertEquals(2, out.size());
        assertTrue(out.stream().anyMatch(r -> "u-bia".equals(r.getUserId()) && !r.isLeadership() && "QA".equals(r.getRoleName())));
    }

    @Test
    void reimportRespeitaQuemOJiraDevolveuEAPessoaDesmarcouNaPrevia() {
        var existing = row("Developer", "DEVELOPER", false, "dani", "dani@x.com", "u-dani", "Dani");
        var jiraSame = row("Developer", "DEVELOPER", false, "dani", "dani@x.com", null, "Dani");

        var out = JiraProfieldsService.mergeWithExistingTeam(List.of(existing), List.of(), List.of(jiraSame));

        assertTrue(out.isEmpty(), "desmarcada na prévia continua fora");
    }

    @Test
    void reimportNaoMantemLiderancaAntigaQueSumiuDoJira() {
        var oldLead = row("Product Owner", "PRODUCT_OWNER", true, "edu", "edu@x.com", "u-edu", "Edu");

        var out = JiraProfieldsService.mergeWithExistingTeam(List.of(oldLead), List.of(), List.of());

        assertTrue(out.isEmpty());
    }

    @Test
    void homonimosNaoSaoTratadosComoAMesmaPessoaNoMerge() {
        var existing = row("QA", "QA", false, null, "joao1@x.com", "u-1", "João Silva");
        var jira = row("Developer", "DEVELOPER", false, "joao2", "joao2@x.com", null, "João Silva");

        var out = JiraProfieldsService.mergeWithExistingTeam(List.of(existing), List.of(jira), List.of(jira));

        assertEquals(2, out.size());
    }

    @Test
    void dedupeMantemPeopleLeadQuandoAPessoaTambemEPO() {
        var po = row("Product Owner", "PRODUCT_OWNER", true, "fe", "fe@x.com", null, "Fê");
        var pl = row("People Lead", "PEOPLE_LEAD", true, "fe", "fe@x.com", null, "Fê");

        var out = JiraProfieldsService.dedupeMembers(new java.util.ArrayList<>(List.of(po, pl)));

        assertEquals(1, out.size());
        assertEquals("PEOPLE_LEAD", out.get(0).getRoleKey());
    }

    @Test
    void mensagemDeErroDoJiraNaoVazaDetalheDeRede() {
        var unauthorized = org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "x", org.springframework.http.HttpHeaders.EMPTY, new byte[0], null);
        assertTrue(JiraProfieldsService.jiraFailureReason(unauthorized).contains("recusou o token"));
        var notFound = org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.NOT_FOUND, "x", org.springframework.http.HttpHeaders.EMPTY, new byte[0], null);
        assertTrue(JiraProfieldsService.jiraFailureReason(notFound).contains("não encontrado"));
        var io = new org.springframework.web.client.ResourceAccessException("I/O error on GET request for \"https://10.1.1.1\"",
                new java.net.SocketTimeoutException("Read timed out"));
        assertFalse(JiraProfieldsService.jiraFailureReason(io).contains("10.1.1.1"));
    }
}
