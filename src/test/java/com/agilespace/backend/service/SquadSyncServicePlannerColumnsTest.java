package com.agilespace.backend.service;

import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.domain.SquadIssueSnapshot;
import com.agilespace.backend.domain.UserJiraConfig;
import com.agilespace.backend.repository.UserJiraConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** V89: o sync da sprint passa a gravar issue_type_id/status_id/own_*, sem mexer no estimate_sec agregado. */
@DisplayName("SquadSyncService - colunas do Sprint Planner e reconciliação protegida")
class SquadSyncServicePlannerColumnsTest {

    @Mock private SquadService squadService;
    @Mock private JiraService jiraService;
    @Mock private SquadCapacityService squadCapacityService;
    @Mock private UserJiraConfigRepository userJiraConfigRepository;
    private SquadSyncService syncService;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        syncService = new SquadSyncService(squadService, jiraService, squadCapacityService, userJiraConfigRepository, new ObjectMapper(), null);
        when(squadService.getSquad("SQ1")).thenReturn(Optional.of(Squad.builder().id("SQ1").name("SQ1").jiraProjectKey("PROJ")
                .syncJql("project = PROJ").defaultDailyCapacityHours(6.0).rankingEnabled(false).build()));
        when(userJiraConfigRepository.findById("u1")).thenReturn(Optional.of(
                UserJiraConfig.builder().userId("u1").domain("empresa.atlassian.net").token("tok").build()));
        when(jiraService.getFields(anyString(), anyString())).thenReturn(ResponseEntity.ok("[]"));
        when(squadService.getMembers("SQ1")).thenReturn(List.of());
        when(squadService.batchUpsertIssues(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(squadService.saveRollup(any())).thenAnswer(inv -> inv.getArgument(0));
        when(squadService.saveSquad(any())).thenAnswer(inv -> inv.getArgument(0));
        String body = "{\"total\":1,\"issues\":[{\"key\":\"A-1\",\"fields\":{\"summary\":\"Pai\","
                + "\"issuetype\":{\"id\":\"10001\",\"name\":\"Story\"},"
                + "\"status\":{\"id\":\"3\",\"name\":\"To Do\",\"statusCategory\":{\"key\":\"new\"}},"
                + "\"timeoriginalestimate\":3600,\"timeestimate\":1800,"
                + "\"aggregatetimeoriginalestimate\":36000,\"aggregatetimeestimate\":18000,"
                + "\"created\":\"2026-01-01T10:00:00.000-0300\",\"updated\":\"2026-01-02T10:00:00.000-0300\"}}]}";
        when(jiraService.searchIssues(any())).thenReturn(ResponseEntity.ok(body));
    }

    @Test
    @DisplayName("grava ids e estimativa própria; estimate_sec continua o agregado")
    @SuppressWarnings("unchecked")
    void fillsPlannerColumnsWithoutChangingAggregate() {
        syncService.syncSquad("SQ1", "u1", true);
        ArgumentCaptor<List<SquadIssueSnapshot>> cap = ArgumentCaptor.forClass(List.class);
        verify(squadService, atLeastOnce()).batchUpsertIssues(eq("SQ1"), cap.capture());
        SquadIssueSnapshot s = cap.getAllValues().stream().flatMap(List::stream)
                .filter(x -> "A-1".equals(x.getJiraKey())).findFirst().orElseThrow();
        assertEquals("10001", s.getIssueTypeId());
        assertEquals("3", s.getStatusId());
        assertEquals(3600L, s.getOwnOriginalEstimateSec());
        assertEquals(1800L, s.getOwnRemainingSec());
        assertEquals(36000L, s.getEstimateSec(), "comportamento antigo: agregado");
        assertEquals(18000L, s.getRemainingSec());
    }

    @Test
    @DisplayName("remoção da reconciliação passa pelo caminho que preserva o backlog do planner")
    void reconciliationUsesProtectedDelete() {
        when(squadService.getIssues("SQ1", null)).thenReturn(List.of(
                SquadIssueSnapshot.builder().jiraKey("A-1").build(), SquadIssueSnapshot.builder().jiraKey("OLD-1").build()));
        syncService.syncSquad("SQ1", "u1", true);
        verify(squadService).batchDeleteIssuesKeepingPlannerBacklog("SQ1", List.of("OLD-1"));
        verify(squadService, never()).batchDeleteIssues(anyString(), any());
    }
}
