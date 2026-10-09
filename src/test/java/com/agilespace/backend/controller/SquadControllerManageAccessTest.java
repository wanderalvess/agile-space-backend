package com.agilespace.backend.controller;

import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.SquadMetricsRollup;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.UserProjectAccessDto;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.SquadAccessService;
import com.agilespace.backend.service.SquadService;
import com.agilespace.backend.service.UserProjectResolverService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Escritas de gestão da squad (config, roster, capacidade, dado nominal) só da liderança; claim e bootstrap. */
@ExtendWith(MockitoExtension.class)
@DisplayName("SquadController - quem gerencia, claim, bootstrap e consulta de vínculo")
class SquadControllerManageAccessTest {

    @Mock private SquadService service;
    @Mock private com.agilespace.backend.service.SquadSyncService squadSyncService;
    @Mock private com.agilespace.backend.service.SquadSyncGuard squadSyncGuard;
    @Mock private UserRepository userRepository;
    @Mock private UserProjectResolverService resolver;

    private SquadController controller;

    @BeforeEach
    void setUp() {
        SquadAccessService access = new SquadAccessService(userRepository, service, resolver);
        controller = new SquadController(service, squadSyncService, squadSyncGuard, null, userRepository, resolver, access);
    }

    private HttpServletRequest as(String userId, String role) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE)).thenReturn(role);
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn(userId);
        return request;
    }

    private User userInSq1(String id) {
        User u = User.builder().id(id).email(id + "@empresa.com.br").squadId("SQ1").build();
        lenient().when(userRepository.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    private void squadHasLeadershipAndCallerIsNot() {
        lenient().when(resolver.resolveUserAccess(any())).thenReturn(UserProjectAccessDto.builder()
                .projects(List.of(UserProjectAccessDto.ProjectAccessItem.builder().projectId("SQ1").isLeadership(false).build())).build());
        lenient().when(resolver.hasRegisteredLeadership("SQ1")).thenReturn(true);
    }

    private void callerIsLeaderOfSq1() {
        lenient().when(resolver.resolveUserAccess(any())).thenReturn(UserProjectAccessDto.builder()
                .projects(List.of(UserProjectAccessDto.ProjectAccessItem.builder().projectId("SQ1").isLeadership(true).build())).build());
    }

    @Test
    @DisplayName("Dev não grava configuração da squad (403) — antes qualquer membro trocava domínio do Jira e JQL")
    void developerCannotSaveSquadConfig() {
        userInSq1("dev1");
        squadHasLeadershipAndCallerIsNot();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.saveSquad("SQ1", Squad.builder().jiraDomain("servidor-do-atacante.exemplo.com").build(), as("dev1", "MEMBER")));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(service, never()).saveSquad(any());
    }

    @Test
    @DisplayName("Dev continua salvando o que o Painel já deixa qualquer membro mexer (cerimônias, unidade de estimativa), mas não o nome")
    void developerStillSavesCeremoniesAndEstimationUnit() {
        userInSq1("dev1");
        squadHasLeadershipAndCallerIsNot();
        when(service.saveSquad(any())).thenAnswer(i -> i.getArgument(0));
        Squad body = Squad.builder().ceremonyMode("manual").estimationUnit("HOURS").name("Nome trocado").build();

        controller.saveSquad("SQ1", body, as("dev1", "MEMBER"));

        org.mockito.ArgumentCaptor<Squad> saved = org.mockito.ArgumentCaptor.forClass(Squad.class);
        verify(service).saveSquad(saved.capture());
        assertEquals("manual", saved.getValue().getCeremonyMode());
        assertEquals("HOURS", saved.getValue().getEstimationUnit());
        assertNull(saved.getValue().getName());
    }

    @Test
    @DisplayName("Dev não liga o ranking, não troca a JQL nem a capacidade, não define fases nem dono do sync")
    void developerCannotTouchEachLeadershipOnlyField() {
        userInSq1("dev1");
        squadHasLeadershipAndCallerIsNot();
        HttpServletRequest dev = as("dev1", "MEMBER");

        for (Squad body : List.of(
                Squad.builder().syncJql("project = X").build(),
                Squad.builder().jiraProjectKey("OUTRO").build(),
                Squad.builder().rankingEnabled(true).build(),
                Squad.builder().defaultDailyCapacityHours(2.0).build(),
                Squad.builder().syncOwnerUserId("dev1").build(),
                Squad.builder().phases(new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode()).build())) {
            assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> controller.saveSquad("SQ1", body, dev)).getStatusCode());
        }
        verify(service, never()).saveSquad(any());
    }

    @Test
    @DisplayName("Liderança grava a config, mas campos do sync (sprint ativa, histórico, status) não vêm do cliente")
    void leaderSavesButServerManagedFieldsAreStripped() {
        userInSq1("am1");
        callerIsLeaderOfSq1();
        when(service.saveSquad(any())).thenAnswer(i -> i.getArgument(0));
        Squad body = Squad.builder().syncJql("project = X").activeSprintId("999").lastSyncStatus("success").lastSyncError("").schemaVersion(2).build();

        controller.saveSquad("SQ1", body, as("am1", "MEMBER"));

        org.mockito.ArgumentCaptor<Squad> saved = org.mockito.ArgumentCaptor.forClass(Squad.class);
        verify(service).saveSquad(saved.capture());
        assertEquals("SQ1", saved.getValue().getId());
        assertEquals("project = X", saved.getValue().getSyncJql());
        assertNull(saved.getValue().getActiveSprintId());
        assertNull(saved.getValue().getLastSyncStatus());
        assertNull(saved.getValue().getLastSyncError());
        assertNull(saved.getValue().getSchemaVersion());
    }

    @Test
    @DisplayName("Ninguém indica outra pessoa como dono do sync agendado (token do Jira de terceiros)")
    void cannotSetAnotherUserAsSyncOwner() {
        userInSq1("am1");
        callerIsLeaderOfSq1();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.saveSquad("SQ1", Squad.builder().syncOwnerUserId("outra-pessoa").build(), as("am1", "MEMBER")));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(service, never()).saveSquad(any());
    }

    @Test
    @DisplayName("A própria pessoa pode ligar o sync agendado em seu nome")
    void canSetSelfAsSyncOwner() {
        userInSq1("am1");
        callerIsLeaderOfSq1();
        when(service.saveSquad(any())).thenAnswer(i -> i.getArgument(0));

        ResponseEntity<Squad> response = controller.saveSquad("SQ1", Squad.builder().syncOwnerUserId("am1").build(), as("am1", "MEMBER"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    @DisplayName("Dev não edita roster, capacidade nem lê horas por pessoa")
    void developerCannotManageRosterOrReadNominalData() {
        userInSq1("dev1");
        squadHasLeadershipAndCallerIsNot();
        HttpServletRequest dev = as("dev1", "MEMBER");

        assertThrows(ResponseStatusException.class, () -> controller.saveMember("SQ1", "acc-1", SquadMember.builder().role("Tech Lead").build(), dev));
        assertThrows(ResponseStatusException.class, () -> controller.deleteMember("SQ1", "acc-1", dev));
        assertThrows(ResponseStatusException.class, () -> controller.batchUpsertMembers("SQ1", List.of(), dev));
        assertThrows(ResponseStatusException.class, () -> controller.savePersonConfig("SQ1", "acc-1", null, new com.agilespace.backend.domain.SquadPersonConfig(), dev));
        assertThrows(ResponseStatusException.class, () -> controller.getMemberMetrics("SQ1", dev));
        assertThrows(ResponseStatusException.class, () -> controller.getWorklogCache("SQ1", null, dev));
        verify(service, never()).saveMember(any(), any(), any());
        verify(service, never()).deleteMember(any(), any());
    }

    @Test
    @DisplayName("Dev ainda lê o roster, o rollup e o board (não é dado nominal sensível)")
    void developerStillReadsSquadData() {
        userInSq1("dev1");
        when(service.getMembers("SQ1")).thenReturn(List.of());
        when(service.getIssues("SQ1", null)).thenReturn(List.of());

        assertEquals(HttpStatus.OK, controller.getMembers("SQ1", as("dev1", "MEMBER")).getStatusCode());
        assertEquals(HttpStatus.OK, controller.getIssues("SQ1", null, as("dev1", "MEMBER")).getStatusCode());
    }

    @Test
    @DisplayName("\"Sou eu\": membro liga a PRÓPRIA conta a uma linha livre, sem ser liderança")
    void memberClaimsOwnRow() {
        userInSq1("dev1");
        squadHasLeadershipAndCallerIsNot();
        when(service.claimMember("SQ1", "acc-1", "dev1")).thenReturn(new SquadMember());

        ResponseEntity<SquadMember> response = controller.saveMember("SQ1", "acc-1",
                SquadMember.builder().claimedByUid("dev1").updatedAt("2026-10-09T10:00:00Z").build(), as("dev1", "MEMBER"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(service).claimMember("SQ1", "acc-1", "dev1");
        verify(service, never()).saveMember(any(), any(), any());
    }

    @Test
    @DisplayName("Claim com a conta de OUTRA pessoa é recusado (403)")
    void cannotClaimOnBehalfOfSomeoneElse() {
        userInSq1("dev1");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.saveMember("SQ1", "acc-1",
                SquadMember.builder().claimedByUid("vitima").build(), as("dev1", "MEMBER")));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(service, never()).claimMember(any(), any(), any());
    }

    @Test
    @DisplayName("Liderança salva a linha, mas o vínculo de conta (claimedByUid) é descartado")
    void leaderSaveMemberDropsClaimedByUid() {
        userInSq1("am1");
        callerIsLeaderOfSq1();
        when(service.saveMember(any(), any(), any())).thenAnswer(i -> i.getArgument(2));
        SquadMember body = SquadMember.builder().displayName("Ana").capacityHoursPerDay(6.0).claimedByUid("outra").build();

        controller.saveMember("SQ1", "acc-1", body, as("am1", "MEMBER"));

        assertNull(body.getClaimedByUid());
    }

    @Test
    @DisplayName("Usuário sem squad NÃO é vinculado a uma squad que já existe")
    void noBootstrapIntoExistingSquad() {
        User loner = User.builder().id("novo").email("novo@empresa.com.br").build();
        when(userRepository.findById("novo")).thenReturn(Optional.of(loner));
        when(service.getSquad("SQ1")).thenReturn(Optional.of(new Squad()));

        assertThrows(ResponseStatusException.class, () -> controller.syncSquad("SQ1", false, as("novo", "MEMBER")));

        assertNull(loner.getSquadId());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(squadSyncService);
    }

    @Test
    @DisplayName("Ambiente limpo: usuário sem squad cria a primeira squad (nova) e é vinculado a ela")
    void bootstrapIntoBrandNewSquad() {
        User loner = User.builder().id("novo").email("novo@empresa.com.br").build();
        when(userRepository.findById("novo")).thenReturn(Optional.of(loner));
        when(service.getSquad("NOVA")).thenReturn(Optional.empty());
        when(service.getMembers("NOVA")).thenReturn(List.of());
        when(squadSyncGuard.tryAcquire("NOVA")).thenReturn(true);

        ResponseEntity<Void> response = controller.syncSquad("NOVA", false, as("novo", "MEMBER"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("NOVA", loner.getSquadId());
        verify(userRepository).save(loner);
    }

    @Test
    @DisplayName("Consultar vínculo de outra pessoa (by-user) é 403; o próprio e-mail ou id passa; admin passa")
    void byUserOnlyForSelfOrAdmin() {
        userInSq1("dev1");
        when(service.getSquadMembersForUser(any())).thenReturn(List.of());

        assertThrows(ResponseStatusException.class, () -> controller.getSquadMembersForUser("alvo@empresa.com.br", as("dev1", "MEMBER")));
        assertEquals(HttpStatus.OK, controller.getSquadMembersForUser("dev1@empresa.com.br", as("dev1", "MEMBER")).getStatusCode());
        assertEquals(HttpStatus.OK, controller.getSquadMembersForUser("dev1", as("dev1", "MEMBER")).getStatusCode());
        assertEquals(HttpStatus.OK, controller.getSquadMembersForUser("qualquer@x.com", as("adm", "ADMIN")).getStatusCode());
    }

    @Test
    @DisplayName("Lista de squads traz só as que a pessoa pode ler")
    void listOnlyReadableSquads() {
        userInSq1("dev1");
        Squad mine = Squad.builder().id("SQ1").name("SQ1").build();
        Squad other = Squad.builder().id("SQ9").name("SQ9").build();
        when(service.getAllSquads()).thenReturn(List.of(mine, other));
        when(service.getMembers(any())).thenReturn(List.of());
        when(resolver.resolveUserAccess(any())).thenReturn(null);

        List<Squad> result = controller.getAllSquads(as("dev1", "MEMBER")).getBody();

        assertEquals(List.of(mine), result);
    }

    @Test
    @DisplayName("Rollup de uma sprint específica (e 404 quando não existe), sem confundir com o da sprint ativa")
    void rollupBySprint() {
        userInSq1("dev1");
        SquadMetricsRollup r = new SquadMetricsRollup();
        when(service.getRollup("SQ1", "42")).thenReturn(Optional.of(r));
        when(service.getRollup("SQ1", "43")).thenReturn(Optional.empty());

        assertSame(r, controller.getRollup("SQ1", "42", as("dev1", "MEMBER")).getBody());
        assertEquals(HttpStatus.NOT_FOUND, controller.getRollup("SQ1", "43", as("dev1", "MEMBER")).getStatusCode());
        verify(service, never()).getRollup("SQ1");
    }
}
