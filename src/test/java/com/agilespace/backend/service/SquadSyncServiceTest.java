package com.agilespace.backend.service;

import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.domain.SquadIssueSnapshot;
import com.agilespace.backend.domain.SquadMetricsRollup;
import com.agilespace.backend.domain.UserJiraConfig;
import com.agilespace.backend.repository.UserJiraConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Cobre principalmente os dois freios de segurança herdados do motor client-side
 * (useSquadStore.ts/squadSync.ts) — abortar a sync em vez de apagar dado em massa —
 * e um caminho feliz de sync completa, ponta a ponta contra mocks de SquadService/JiraService.
 */
public class SquadSyncServiceTest {

    @Mock private SquadService squadService;
    @Mock private JiraService jiraService;
    @Mock private SquadCapacityService squadCapacityService;
    @Mock private UserJiraConfigRepository userJiraConfigRepository;

    private SquadSyncService syncService;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        syncService = new SquadSyncService(squadService, jiraService, squadCapacityService, userJiraConfigRepository, new ObjectMapper());
    }

    private Squad baseSquad() {
        return Squad.builder()
                .id("SQ1").name("SQ1").jiraProjectKey("PROJ").syncJql("project = PROJ")
                .defaultDailyCapacityHours(6.0).rankingEnabled(false)
                .build();
    }

    private UserJiraConfig creds() {
        return UserJiraConfig.builder().userId("u1").domain("empresa.atlassian.net").token("tok").build();
    }

    private String issuesResponse(String... keys) {
        StringBuilder issues = new StringBuilder();
        for (String key : keys) {
            if (issues.length() > 0) issues.append(",");
            issues.append("{\"key\":\"").append(key).append("\",\"fields\":{")
                    .append("\"issuetype\":{\"name\":\"Story\"},")
                    .append("\"status\":{\"name\":\"To Do\",\"statusCategory\":{\"key\":\"new\"}},")
                    .append("\"created\":\"2026-01-01T10:00:00.000-0300\",")
                    .append("\"updated\":\"2026-01-02T10:00:00.000-0300\"}}");
        }
        return "{\"total\":" + keys.length + ",\"issues\":[" + issues + "]}";
    }

    private String bugIssueJson(String key, String created, String resolutionDate) {
        return "{\"key\":\"" + key + "\",\"fields\":{"
                + "\"issuetype\":{\"name\":\"Bug\"},"
                + "\"status\":{\"name\":\"To Do\",\"statusCategory\":{\"key\":\"new\"}},"
                + "\"created\":\"" + created + "\","
                + (resolutionDate != null ? "\"resolutiondate\":\"" + resolutionDate + "\"," : "")
                + "\"updated\":\"" + created + "\"}}";
    }

    private void stubCommonSquadServiceCalls() {
        when(squadService.getMembers("SQ1")).thenReturn(List.of());
        when(squadService.batchUpsertIssues(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(squadService.saveRollup(any())).thenAnswer(inv -> inv.getArgument(0));
        when(squadService.saveSquad(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    public void syncSquad_throwsWhenNoJiraCredentialsConfigured() {
        when(squadService.getSquad("SQ1")).thenReturn(Optional.of(baseSquad()));
        when(userJiraConfigRepository.findById("u1")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> syncService.syncSquad("SQ1", "u1", false));

        assertTrue(ex.getReason().contains("Token de Acesso do Jira"));
        verifyNoInteractions(jiraService);
    }

    @Test
    public void syncSquad_migrationSafety_abortsWhenZeroIssuesReturnedButExistingOnes() {
        when(squadService.getSquad("SQ1")).thenReturn(Optional.of(baseSquad()));
        when(userJiraConfigRepository.findById("u1")).thenReturn(Optional.of(creds()));
        when(jiraService.getFields(anyString(), anyString())).thenReturn(ResponseEntity.ok("[]"));
        when(jiraService.searchIssues(any())).thenReturn(ResponseEntity.ok(issuesResponse()));
        when(squadService.getIssues("SQ1", null)).thenReturn(List.of(
                SquadIssueSnapshot.builder().dbId("SQ1_A-1").squadId("SQ1").jiraKey("A-1").build()));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> syncService.syncSquad("SQ1", "u1", true));

        assertTrue(ex.getReason().contains("0 issues"));
        verify(squadService, never()).batchDeleteIssues(anyString(), any());
    }

    @Test
    public void syncSquad_migrationSafety_abortsWhenWouldDeleteMoreThan90Percent() {
        when(squadService.getSquad("SQ1")).thenReturn(Optional.of(baseSquad()));
        when(userJiraConfigRepository.findById("u1")).thenReturn(Optional.of(creds()));
        when(jiraService.getFields(anyString(), anyString())).thenReturn(ResponseEntity.ok("[]"));
        // Nenhuma das 10 issues existentes bate com a única issue nova (Z-1) — apagaria
        // 100% da partição de uma vez, bem acima do teto de 90% que aciona o abortar.
        when(jiraService.searchIssues(any())).thenReturn(ResponseEntity.ok(issuesResponse("Z-1")));
        List<SquadIssueSnapshot> tenExisting = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tenExisting.add(SquadIssueSnapshot.builder().dbId("SQ1_A-" + i).squadId("SQ1").jiraKey("A-" + i).build());
        }
        when(squadService.getIssues("SQ1", null)).thenReturn(tenExisting);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> syncService.syncSquad("SQ1", "u1", true));

        assertTrue(ex.getReason().contains("abortado por segurança"));
    }

    @Test
    public void syncSquad_happyPath_persistsRollupAndMarksConfigSuccess() {
        when(squadService.getSquad("SQ1")).thenReturn(Optional.of(baseSquad()));
        when(userJiraConfigRepository.findById("u1")).thenReturn(Optional.of(creds()));
        when(jiraService.getFields(anyString(), anyString())).thenReturn(ResponseEntity.ok("[]"));
        when(jiraService.searchIssues(any())).thenReturn(ResponseEntity.ok(issuesResponse("A-1", "A-2")));
        when(squadService.getIssues(eq("SQ1"), any())).thenReturn(List.of());
        stubCommonSquadServiceCalls();

        syncService.syncSquad("SQ1", "u1", false);

        ArgumentCaptor<SquadMetricsRollup> rollupCaptor = ArgumentCaptor.forClass(SquadMetricsRollup.class);
        verify(squadService).saveRollup(rollupCaptor.capture());
        assertEquals(2, rollupCaptor.getValue().getTotalIssues());
        assertEquals("SQ1_UNMAPPED", rollupCaptor.getValue().getDbId());

        ArgumentCaptor<Squad> squadCaptor = ArgumentCaptor.forClass(Squad.class);
        verify(squadService, atLeastOnce()).saveSquad(squadCaptor.capture());
        Squad lastUpdate = squadCaptor.getAllValues().get(squadCaptor.getAllValues().size() - 1);
        assertEquals("success", lastUpdate.getLastSyncStatus());
        assertEquals("u1", lastUpdate.getLastSyncBy());
        assertEquals(2, lastUpdate.getLastSyncIssueCount());
        assertEquals("", lastUpdate.getLastSyncError());
    }

    @Test
    public void forceResyncSprint_throwsWhenSquadMissing() {
        when(squadService.getSquad("SQ1")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> syncService.forceResyncSprint("SQ1", "u1", "SPRINT-1"));
    }

    @Test
    public void forceResyncSprint_targetsOnlyTheGivenSprintPartition() {
        Squad squad = baseSquad();
        when(squadService.getSquad("SQ1")).thenReturn(Optional.of(squad));
        when(userJiraConfigRepository.findById("u1")).thenReturn(Optional.of(creds()));
        when(jiraService.getFields(anyString(), anyString())).thenReturn(ResponseEntity.ok("[]"));
        when(jiraService.searchIssues(any())).thenReturn(ResponseEntity.ok(issuesResponse("A-1")));
        when(jiraService.getSprint(anyString(), anyString(), eq("SPRINT-9"))).thenReturn(ResponseEntity.ok(
                "{\"id\":\"SPRINT-9\",\"name\":\"Sprint 9\",\"state\":\"CLOSED\",\"startDate\":\"2026-01-01T00:00:00.000Z\",\"endDate\":\"2026-01-14T00:00:00.000Z\"}"));
        when(squadService.getIssues("SQ1", "SPRINT-9")).thenReturn(List.of());
        stubCommonSquadServiceCalls();

        syncService.forceResyncSprint("SQ1", "u1", "SPRINT-9");

        ArgumentCaptor<SquadMetricsRollup> rollupCaptor = ArgumentCaptor.forClass(SquadMetricsRollup.class);
        verify(squadService).saveRollup(rollupCaptor.capture());
        assertEquals("SPRINT-9", rollupCaptor.getValue().getSprintId());
        assertEquals(1, rollupCaptor.getValue().getTotalIssues());
        // forceResyncSprint nunca deve mexer em activeSprintId — é escape hatch pra sprint
        // encerrada, não pode pisar em qual sprint a tela mostra como "atual".
        verify(squadService, never()).saveSquad(argThat(s -> s.getActiveSprintId() != null));
    }

    @Test
    public void forceResyncSprint_computesBugEscapeRateWithinTheSprintWindow() {
        Squad squad = baseSquad();
        when(squadService.getSquad("SQ1")).thenReturn(Optional.of(squad));
        when(userJiraConfigRepository.findById("u1")).thenReturn(Optional.of(creds()));
        when(jiraService.getFields(anyString(), anyString())).thenReturn(ResponseEntity.ok("[]"));
        // Bug-1: criado e resolvido dentro da janela da sprint (2026-02-01 a 2026-02-14) —
        // "escapou" mas já foi corrigido. Bug-2: criado dentro da janela, sem resolutiondate —
        // ainda aberto. Bug-3: criado ANTES da janela — não conta como escape desta sprint.
        String issuesJson = "{\"total\":3,\"issues\":["
                + bugIssueJson("BUG-1", "2026-02-02T10:00:00.000-0300", "2026-02-05T10:00:00.000-0300") + ","
                + bugIssueJson("BUG-2", "2026-02-10T10:00:00.000-0300", null) + ","
                + bugIssueJson("BUG-3", "2026-01-20T10:00:00.000-0300", null)
                + "]}";
        when(jiraService.searchIssues(any())).thenReturn(ResponseEntity.ok(issuesJson));
        when(jiraService.getSprint(anyString(), anyString(), eq("SPRINT-9"))).thenReturn(ResponseEntity.ok(
                "{\"id\":\"SPRINT-9\",\"name\":\"Sprint 9\",\"state\":\"CLOSED\",\"startDate\":\"2026-02-01T00:00:00.000Z\",\"endDate\":\"2026-02-14T00:00:00.000Z\"}"));
        when(squadService.getIssues("SQ1", "SPRINT-9")).thenReturn(List.of());
        stubCommonSquadServiceCalls();

        syncService.forceResyncSprint("SQ1", "u1", "SPRINT-9");

        ArgumentCaptor<SquadMetricsRollup> rollupCaptor = ArgumentCaptor.forClass(SquadMetricsRollup.class);
        verify(squadService).saveRollup(rollupCaptor.capture());
        com.fasterxml.jackson.databind.JsonNode bugEscapeRate = rollupCaptor.getValue().getExtraMetrics().get("bugEscapeRate");
        assertEquals(2, bugEscapeRate.get("created").asInt());
        assertEquals(1, bugEscapeRate.get("resolvedInSprint").asInt());
        assertEquals(1, bugEscapeRate.get("stillOpen").asInt());
    }

    @Test
    public void forceResyncSprint_classifiesScopeChurnFromSprintFieldChangelog() {
        Squad squad = baseSquad();
        when(squadService.getSquad("SQ1")).thenReturn(Optional.of(squad));
        when(userJiraConfigRepository.findById("u1")).thenReturn(Optional.of(creds()));
        when(jiraService.getFields(anyString(), anyString())).thenReturn(ResponseEntity.ok("[]"));
        // PLANNED-1: criada antes do início da sprint, sem transição de Sprint no changelog
        // -> addedDate cai no fallback (created), que é antes do início -> planejada.
        String planned = "{\"key\":\"PLANNED-1\",\"fields\":{"
                + "\"issuetype\":{\"name\":\"Story\"},\"status\":{\"name\":\"To Do\",\"statusCategory\":{\"key\":\"new\"}},"
                + "\"created\":\"2026-01-15T10:00:00.000-0300\",\"updated\":\"2026-01-15T10:00:00.000-0300\"}}";
        // ADDED-1: criada antes do inicio, mas o changelog mostra que só entrou na sprint
        // DEPOIS do início (2026-02-05 > 2026-02-01) -> adicionada após o início.
        String added = "{\"key\":\"ADDED-1\",\"fields\":{"
                + "\"issuetype\":{\"name\":\"Story\"},\"status\":{\"name\":\"To Do\",\"statusCategory\":{\"key\":\"new\"}},"
                + "\"created\":\"2026-01-10T10:00:00.000-0300\",\"updated\":\"2026-02-05T10:00:00.000-0300\"},"
                + "\"changelog\":{\"histories\":[{\"created\":\"2026-02-05T09:00:00.000-0300\",\"items\":["
                + "{\"field\":\"Sprint\",\"from\":\"\",\"fromString\":\"\",\"to\":\"SPRINT-9\",\"toString\":\"Sprint 9\"}]}]}}";
        when(jiraService.searchIssues(any())).thenReturn(ResponseEntity.ok(
                "{\"total\":2,\"issues\":[" + planned + "," + added + "]}"));
        when(jiraService.getSprint(anyString(), anyString(), eq("SPRINT-9"))).thenReturn(ResponseEntity.ok(
                "{\"id\":\"SPRINT-9\",\"name\":\"Sprint 9\",\"state\":\"CLOSED\",\"startDate\":\"2026-02-01T00:00:00.000Z\",\"endDate\":\"2026-02-14T00:00:00.000Z\"}"));
        when(squadService.getIssues("SQ1", "SPRINT-9")).thenReturn(List.of());
        stubCommonSquadServiceCalls();

        syncService.forceResyncSprint("SQ1", "u1", "SPRINT-9");

        ArgumentCaptor<SquadMetricsRollup> rollupCaptor = ArgumentCaptor.forClass(SquadMetricsRollup.class);
        verify(squadService).saveRollup(rollupCaptor.capture());
        com.fasterxml.jackson.databind.JsonNode churn = rollupCaptor.getValue().getExtraMetrics().get("scopeChurn");
        assertEquals(1, churn.get("planned").asInt());
        assertEquals(1, churn.get("added").asInt());
        assertEquals(0, churn.get("carryover").asInt());
    }

    @Test
    public void forceResyncSprint_computesCycleTimeByStatusUsingAssigneeCapacity() {
        Squad squad = baseSquad();
        when(squadService.getSquad("SQ1")).thenReturn(Optional.of(squad));
        when(userJiraConfigRepository.findById("u1")).thenReturn(Optional.of(creds()));
        when(jiraService.getFields(anyString(), anyString())).thenReturn(ResponseEntity.ok("[]"));
        // Issue passou por "Em Andamento" de 2026-02-02 09:00 a 2026-02-03 09:00 (1 dia útil
        // inteiro), depois resolvida — 8h produtivas/dia, janela 8h-18h (10h) -> prodRatio=0.8
        // -> 1 dia útil inteiro (10h de janela) * 0.8 = 8h produtivas nesse status.
        String issueJson = "{\"key\":\"CT-1\",\"fields\":{"
                + "\"issuetype\":{\"name\":\"Story\"},\"status\":{\"name\":\"Concluído\",\"statusCategory\":{\"key\":\"done\"}},"
                + "\"assignee\":{\"accountId\":\"acc-1\",\"displayName\":\"Fulano\"},"
                + "\"created\":\"2026-02-02T09:00:00.000-0300\",\"updated\":\"2026-02-03T09:00:00.000-0300\","
                + "\"resolutiondate\":\"2026-02-03T09:00:00.000-0300\"},"
                + "\"changelog\":{\"histories\":[{\"created\":\"2026-02-03T09:00:00.000-0300\",\"items\":["
                + "{\"field\":\"status\",\"from\":\"3\",\"fromString\":\"Em Andamento\",\"to\":\"10\",\"toString\":\"Concluído\"}]}]}}";
        when(jiraService.searchIssues(any())).thenReturn(ResponseEntity.ok("{\"total\":1,\"issues\":[" + issueJson + "]}"));
        when(jiraService.getSprint(anyString(), anyString(), eq("SPRINT-9"))).thenReturn(ResponseEntity.ok(
                "{\"id\":\"SPRINT-9\",\"name\":\"Sprint 9\",\"state\":\"CLOSED\",\"startDate\":\"2026-02-01T00:00:00.000Z\",\"endDate\":\"2026-02-14T00:00:00.000Z\"}"));
        when(squadService.getIssues("SQ1", "SPRINT-9")).thenReturn(List.of());
        when(squadCapacityService.resolve("SQ1", "SPRINT-9", "acc-1")).thenReturn(new SquadCapacityService.ResolvedPersonConfig(
                "acc-1", "DEV", 5, 0, 8.0, false, false, false, false, null, null, null, null));
        stubCommonSquadServiceCalls();

        syncService.forceResyncSprint("SQ1", "u1", "SPRINT-9");

        ArgumentCaptor<SquadMetricsRollup> rollupCaptor = ArgumentCaptor.forClass(SquadMetricsRollup.class);
        verify(squadService).saveRollup(rollupCaptor.capture());
        com.fasterxml.jackson.databind.JsonNode cycleTime = rollupCaptor.getValue().getExtraMetrics().get("cycleTimeByStatus");
        assertEquals(8.0, cycleTime.get("Em Andamento").asDouble(), 0.01);
    }
}
