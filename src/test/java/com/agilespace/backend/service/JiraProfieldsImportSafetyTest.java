package com.agilespace.backend.service;

import com.agilespace.backend.domain.ProjectMemberRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reimportação não apaga quem entrou fora do Jira, nem liderança cadastrada à mão, e mantém os ids das linhas
 * (TeamImportPlanner); dedupe não perde AM/PL; mensagens de erro do Jira.
 */
class JiraProfieldsImportSafetyTest {

    private ProjectMemberRole row(String role, String key, boolean lead, String account, String email, String userId, String name) {
        return ProjectMemberRole.builder().projectId("P").roleName(role).roleKey(key).isLeadership(lead)
                .jiraAccountId(account).email(email).userId(userId).displayName(name).build();
    }

    private ProjectMemberRole withId(ProjectMemberRole r, String id, String source) {
        r.setId(id);
        r.setSource(source);
        return r;
    }

    private TeamImportPlanner.Plan plan(List<ProjectMemberRole> existing, List<ProjectMemberRole> incoming, List<ProjectMemberRole> jira) {
        return TeamImportPlanner.plan(existing, incoming, jira, null);
    }

    @Test
    void reimportMantemOVinculoDeContaEOIdDaLinhaDaMesmaPessoa() {
        var old = withId(row("Developer", "DEVELOPER", false, "ana", "ana@x.com", "u-ana", "Ana"), "pmr-ana", null);
        var incoming = row("Developer", "DEVELOPER", false, "ana", "ANA@x.com", null, "Ana Souza");

        var plan = plan(List.of(old), List.of(incoming), List.of(incoming));

        assertEquals(1, plan.finalRows().size());
        assertSame(old, plan.finalRows().get(0), "atualiza no lugar: mesma linha, mesmo id");
        assertEquals("pmr-ana", plan.finalRows().get(0).getId());
        assertEquals("u-ana", plan.finalRows().get(0).getUserId());
        assertTrue(plan.toInsert().isEmpty());
        assertTrue(plan.toDelete().isEmpty());
    }

    @Test
    void reimportMantemQuemEntrouPorSouEuOuJoinEQueONaoEstaNoJira() {
        var joined = withId(row("QA", "QA", false, null, "bia@x.com", "u-bia", "Bia"), "pmr-bia", "CLAIM");
        var jiraPerson = row("Developer", "DEVELOPER", false, "caio", "caio@x.com", null, "Caio");

        var plan = plan(List.of(joined), List.of(jiraPerson), List.of(jiraPerson));

        assertEquals(2, plan.finalRows().size());
        assertTrue(plan.finalRows().stream().anyMatch(r -> "u-bia".equals(r.getUserId()) && !r.isLeadership() && "QA".equals(r.getRoleName())));
        assertTrue(plan.toDelete().isEmpty());
    }

    @Test
    void reimportRespeitaQuemOJiraDevolveuEAPessoaDesmarcouNaPrevia() {
        var existing = withId(row("Developer", "DEVELOPER", false, "dani", "dani@x.com", "u-dani", "Dani"), "pmr-dani", null);
        var jiraSame = row("Developer", "DEVELOPER", false, "dani", "dani@x.com", null, "Dani");

        var plan = plan(List.of(existing), List.of(), List.of(jiraSame));

        assertEquals(List.of(existing), plan.toDelete(), "desmarcada na prévia (linha do roster) continua fora");
        assertTrue(plan.finalRows().isEmpty());
    }

    @Test
    void desmarcadoNaPreviaNaoApagaLinhaDeOrigemProtegida() {
        var manual = withId(row("People Lead", "PEOPLE_LEAD", true, "gus", "gus@x.com", null, "Gus"), "pmr-gus", "MANUAL");
        var jiraSame = row("People Lead", "PEOPLE_LEAD", true, "gus", "gus@x.com", null, "Gus");

        var plan = plan(List.of(manual), List.of(), List.of(jiraSame));

        assertTrue(plan.toDelete().isEmpty());
        assertEquals(List.of(manual), plan.finalRows());
    }

    @Test
    void liderancaManualSobreviveAReimportacaoMesmoSemUserId() {
        // Fundador de createManualProject / PO adicionado em SquadTeamService.addMember: o Jira não lista essa pessoa.
        var manualPo = withId(row("Product Owner", "PRODUCT_OWNER", true, null, "po@x.com", null, "PO Manual"), "pmr-po", "MANUAL");
        var jiraDev = row("Developer", "DEVELOPER", false, "caio", "caio@x.com", null, "Caio");

        var plan = plan(List.of(manualPo), List.of(jiraDev), List.of(jiraDev));

        assertTrue(plan.toDelete().isEmpty());
        assertTrue(plan.finalRows().contains(manualPo));
        assertTrue(manualPo.isLeadership());
        assertEquals("pmr-po", manualPo.getId());
    }

    @Test
    void liderancaLegadaComContaSobreviveQuandoOJiraDeixaDeListar() {
        // Mudança de regra (etapa 0): antes a reimportação apagava liderança fora do Jira mesmo com conta vinculada.
        var oldLead = withId(row("Product Owner", "PRODUCT_OWNER", true, "edu", "edu@x.com", "u-edu", "Edu"), "pmr-edu", null);

        var plan = plan(List.of(oldLead), List.of(), List.of());

        assertTrue(plan.toDelete().isEmpty(), "linha com user_id nunca é removida só porque o Jira sumiu com ela");
        assertEquals(List.of(oldLead), plan.finalRows());
    }

    @Test
    void linhaDoJiraSemContaSomeQuandoOJiraDeixaDeListar() {
        var gone = withId(row("Developer", "DEVELOPER", false, "fim", "fim@x.com", null, "Fim"), "pmr-fim", "JIRA");
        var legacyGone = withId(row("QA", "QA", false, "leg", "leg@x.com", null, "Leg"), "pmr-leg", null);
        var stays = row("Developer", "DEVELOPER", false, "ana", "ana@x.com", null, "Ana");

        var plan = plan(List.of(gone, legacyGone), List.of(stays), List.of(stays));

        assertEquals(2, plan.toDelete().size());
        assertTrue(plan.toDelete().containsAll(List.of(gone, legacyGone)));
        assertEquals(1, plan.toInsert().size());
        assertEquals("JIRA", plan.toInsert().get(0).getSource(), "linha nova da importação nasce com origem JIRA");
    }

    @Test
    void linhasDeOrigemProtegidaSemContaNuncaSaemPelaReimportacao() {
        for (String source : List.of("MANUAL", "INVITE", "CLAIM", "APPROVED", "MIGRATION")) {
            var protectedRow = withId(row("Developer", "DEVELOPER", false, null, source.toLowerCase() + "@x.com", null, source), "pmr-" + source, source);
            var plan = plan(List.of(protectedRow), List.of(), List.of());
            assertTrue(plan.toDelete().isEmpty(), source + " deve ser preservada");
        }
    }

    @Test
    void idsDasLinhasSaoEstaveisEmReimportacoesRepetidas() {
        var a = withId(row("Developer", "DEVELOPER", false, "ana", "ana@x.com", null, "Ana"), "id-a", "JIRA");
        var b = withId(row("Product Owner", "PRODUCT_OWNER", true, "bia", "bia@x.com", "u-bia", "Bia"), "id-b", "JIRA");
        List<ProjectMemberRole> table = List.of(a, b);

        for (int i = 0; i < 3; i++) {
            var incomingA = row("Developer", "DEVELOPER", false, "ana", "ana@x.com", null, "Ana");
            var incomingB = row("Product Owner", "PRODUCT_OWNER", true, "bia", "bia@x.com", null, "Bia");
            var plan = plan(table, List.of(incomingA, incomingB), List.of(incomingA, incomingB));
            assertTrue(plan.toInsert().isEmpty());
            assertTrue(plan.toDelete().isEmpty());
            assertEquals(List.of("id-a", "id-b"), plan.finalRows().stream().map(ProjectMemberRole::getId).toList());
        }
        assertEquals("u-bia", b.getUserId());
    }

    @Test
    void papelDoJiraAtualizaLinhaDoRosterNoLugarSemTrocarOId() {
        // Promoção no Jira: Developer vira Product Owner. A linha do roster segue o Jira e mantém o id e a conta.
        var dev = withId(row("Developer", "DEVELOPER", false, "ana", "ana@x.com", "u-ana", "Ana"), "pmr-ana", "CLAIM");
        var po = row("Product Owner", "PRODUCT_OWNER", true, "ana", "ana@x.com", null, "Ana");

        var plan = plan(List.of(dev), List.of(po), List.of(po));

        assertEquals(1, plan.finalRows().size());
        assertSame(dev, plan.finalRows().get(0));
        assertEquals("PRODUCT_OWNER", dev.getRoleKey());
        assertTrue(dev.isLeadership());
        assertEquals("u-ana", dev.getUserId());
        assertEquals("pmr-ana", dev.getId());
    }

    @Test
    void liderancaManualNaoEhRebaixadaPeloJiraEPessoaGanhaLinhaExtra() {
        // Mesma pessoa: People Lead cadastrado à mão e Developer no Jira. Cada papel fica na sua linha; liderança vence no resolvedor.
        var manualPl = withId(row("People Lead", "PEOPLE_LEAD", true, "gus", "gus@x.com", "u-gus", "Gus"), "pmr-gus-pl", "MANUAL");
        var jiraDev = row("Developer", "DEVELOPER", false, "gus", "gus@x.com", null, "Gus");

        var plan = plan(List.of(manualPl), List.of(jiraDev), List.of(jiraDev));

        assertEquals(2, plan.finalRows().size());
        assertTrue(manualPl.isLeadership());
        assertEquals("PEOPLE_LEAD", manualPl.getRoleKey());
        assertEquals(1, plan.toInsert().size());
        assertEquals("DEVELOPER", plan.toInsert().get(0).getRoleKey());
        assertTrue(plan.toDelete().isEmpty());
    }

    @Test
    void linhaManualDeMesmoPapelSoGanhaOQueFaltava() {
        var manual = withId(row("Product Owner", "PRODUCT_OWNER", true, null, "po@x.com", "u-po", "PO"), "pmr-po", "MANUAL");
        var jiraPo = row("Product Owner", "PRODUCT_OWNER", true, "po-acc", "po@x.com", null, "Nome do Jira");
        jiraPo.setAvatarUrl("data:image/png;base64,AAA");

        var plan = plan(List.of(manual), List.of(jiraPo), List.of(jiraPo));

        assertEquals(1, plan.finalRows().size());
        assertEquals("PO", manual.getDisplayName(), "nome já preenchido numa linha manual não é sobrescrito");
        assertEquals("po-acc", manual.getJiraAccountId());
        assertEquals("data:image/png;base64,AAA", manual.getAvatarUrl());
        assertEquals("MANUAL", manual.getSource());
    }

    @Test
    void homonimosNaoSaoTratadosComoAMesmaPessoaNoMerge() {
        var existing = withId(row("QA", "QA", false, null, "joao1@x.com", "u-1", "João Silva"), "pmr-1", null);
        var jira = row("Developer", "DEVELOPER", false, "joao2", "joao2@x.com", null, "João Silva");

        var plan = plan(List.of(existing), List.of(jira), List.of(jira));

        assertEquals(2, plan.finalRows().size());
        assertTrue(plan.toDelete().isEmpty());
    }

    @Test
    void quemAlideranciaRemoveuAMaoNaoVoltaEALinhaDoRosterSai() {
        var removedRow = withId(row("Developer", "DEVELOPER", false, "zed", "zed@x.com", null, "Zed"), "pmr-zed", "JIRA");
        var incomingZed = row("Developer", "DEVELOPER", false, "zed", "zed@x.com", null, "Zed");
        BiPredicate<String, String> removed = (acc, mail) -> "zed".equals(acc);

        var plan = TeamImportPlanner.plan(List.of(removedRow), List.of(incomingZed), List.of(incomingZed), removed);

        assertTrue(plan.toInsert().isEmpty());
        assertEquals(List.of(removedRow), plan.toDelete());
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
