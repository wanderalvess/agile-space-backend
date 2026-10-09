package com.agilespace.backend.service;

import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.domain.SquadMetricsRollup;
import com.agilespace.backend.domain.UserJiraConfig;
import com.agilespace.backend.repository.UserJiraConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("SquadSyncService - falha registrada, delta sem apagar métricas, domínio do token e JQL")
public class SquadSyncServiceHardeningTest {

    @Mock private SquadService squadService;
    @Mock private JiraService jiraService;
    @Mock private SquadCapacityService squadCapacityService;
    @Mock private UserJiraConfigRepository userJiraConfigRepository;

    private SquadSyncService syncService;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        syncService = new SquadSyncService(squadService, jiraService, squadCapacityService, userJiraConfigRepository, mapper);
    }

    private UserJiraConfig creds() {
        return UserJiraConfig.builder().userId("u1").domain("empresa.atlassian.net").token("tok").build();
    }

    @Test
    @DisplayName("Falha na sincronização é gravada na squad (status error + motivo), para a tela mostrar e o próximo sync ser completo")
    void failureIsRecorded() {
        Squad squad = Squad.builder().id("SQ1").name("SQ1").jiraProjectKey("P").syncJql("project = P").build();
        when(squadService.getSquad("SQ1")).thenReturn(Optional.of(squad));
        when(userJiraConfigRepository.findById("u1")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () -> syncService.syncSquad("SQ1", "u1", false));

        ArgumentCaptor<Squad> captor = ArgumentCaptor.forClass(Squad.class);
        verify(squadService, atLeastOnce()).saveSquad(captor.capture());
        Squad last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals("error", last.getLastSyncStatus());
        assertTrue(last.getLastSyncError().contains("Token de Acesso do Jira"));
        assertNull(last.getActiveSprintId());
    }

    @Test
    @DisplayName("O token vai para o domínio salvo na conta de quem sincroniza, não para o domínio editado na squad")
    void tokenGoesOnlyToTheDomainSavedOnTheAccount() {
        Squad squad = Squad.builder().id("SQ1").name("SQ1").jiraProjectKey("P").syncJql("project = P")
                .jiraDomain("servidor-do-atacante.exemplo.com").build();
        when(squadService.getSquad("SQ1")).thenReturn(Optional.of(squad));
        when(userJiraConfigRepository.findById("u1")).thenReturn(Optional.of(creds()));
        when(jiraService.getFields(anyString(), anyString())).thenReturn(ResponseEntity.ok("[]"));
        when(jiraService.searchIssues(any())).thenReturn(ResponseEntity.ok("{\"total\":0,\"issues\":[]}"));
        when(squadService.getMembers("SQ1")).thenReturn(List.of());
        when(squadService.getIssues(eq("SQ1"), any())).thenReturn(List.of());
        when(squadService.saveSquad(any())).thenAnswer(i -> i.getArgument(0));

        syncService.syncSquad("SQ1", "u1", false);

        verify(jiraService, atLeastOnce()).getFields(eq("empresa.atlassian.net"), eq("tok"));
        verify(jiraService, never()).getFields(eq("servidor-do-atacante.exemplo.com"), anyString());
    }

    @Test
    @DisplayName("Sprint com JQL embutida (espaço, OR, parênteses) é recusada antes de falar com o Jira")
    void forceResyncRejectsJqlInjection() {
        Squad squad = Squad.builder().id("SQ1").name("SQ1").jiraProjectKey("P").syncJql("project = P AND sprint in openSprints()").build();
        when(squadService.getSquad("SQ1")).thenReturn(Optional.of(squad));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> syncService.forceResyncSprint("SQ1", "u1", "1 OR project = SECRETO"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verifyNoInteractions(jiraService);
    }

    @Test
    @DisplayName("Sync delta mantém scope churn / cycle time do último sync completo (o delta não tem changelog)")
    void deltaKeepsFullSyncOnlyMetrics() throws Exception {
        String now = Instant.now().toString();
        Squad squad = Squad.builder().id("SQ1").name("SQ1").jiraProjectKey("P").syncJql("project = P AND sprint in openSprints()")
                .sprintFieldId("customfield_10020").activeSprintId("7").schemaVersion(2).lastSyncStatus("success")
                .lastSyncAt(now).lastFullReconcileAt(now).defaultDailyCapacityHours(6.0).build();
        when(squadService.getSquad("SQ1")).thenReturn(Optional.of(squad));
        when(userJiraConfigRepository.findById("u1")).thenReturn(Optional.of(creds()));
        String issue = "{\"key\":\"A-1\",\"fields\":{\"summary\":\"Titulo\",\"issuetype\":{\"name\":\"Story\"},"
                + "\"status\":{\"name\":\"To Do\",\"statusCategory\":{\"key\":\"new\"}},"
                + "\"created\":\"2026-01-01T10:00:00.000-0300\",\"updated\":\"2026-01-02T10:00:00.000-0300\","
                + "\"customfield_10020\":[{\"id\":7,\"name\":\"Sprint 7\",\"state\":\"active\","
                + "\"startDate\":\"2026-01-05T10:00:00.000Z\",\"endDate\":\"2026-01-19T10:00:00.000Z\"}]}}";
        when(jiraService.searchIssues(any())).thenReturn(ResponseEntity.ok("{\"total\":1,\"issues\":[" + issue + "]}"));
        when(squadService.getMembers("SQ1")).thenReturn(List.of());
        when(squadService.getIssues(eq("SQ1"), any())).thenReturn(List.of());
        when(squadService.batchUpsertIssues(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(squadService.saveSquad(any())).thenAnswer(i -> i.getArgument(0));
        when(squadService.saveRollup(any())).thenAnswer(i -> i.getArgument(0));
        SquadMetricsRollup previous = new SquadMetricsRollup();
        previous.setExtraMetrics(mapper.readTree("{\"scopeChurn\":{\"planned\":9,\"added\":2,\"removed\":1,\"total\":11},\"cycleTimeByStatus\":{\"Em Review\":12.5},\"estimateAdjustedTotalSec\":7200}"));
        when(squadService.getRollup("SQ1", "7")).thenReturn(Optional.of(previous));

        syncService.syncSquad("SQ1", "u1", false);

        ArgumentCaptor<SquadMetricsRollup> captor = ArgumentCaptor.forClass(SquadMetricsRollup.class);
        verify(squadService).saveRollup(captor.capture());
        var extra = captor.getValue().getExtraMetrics();
        assertEquals(9, extra.path("scopeChurn").path("planned").asInt());
        assertEquals(12.5, extra.path("cycleTimeByStatus").path("Em Review").asDouble());
        assertEquals(7200, extra.path("estimateAdjustedTotalSec").asLong());
        verify(jiraService, times(1)).searchIssues(any());
    }
}
