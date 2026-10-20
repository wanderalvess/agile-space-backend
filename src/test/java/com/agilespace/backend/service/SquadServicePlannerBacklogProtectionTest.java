package com.agilespace.backend.service;

import com.agilespace.backend.domain.SquadIssueSnapshot;
import com.agilespace.backend.repository.SquadIssueSnapshotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Risco 1 do plano técnico: a reconciliação do sync da sprint não pode apagar o backlog do Sprint Planner. */
@ExtendWith(MockitoExtension.class)
@DisplayName("SquadService - reconciliação do sync da sprint preserva o backlog do Sprint Planner")
class SquadServicePlannerBacklogProtectionTest {

    @Mock private SquadIssueSnapshotRepository issueSnapshotRepository;
    @InjectMocks private SquadService service;

    @Test
    @DisplayName("chave que sumiu da JQL mas está no backlog vira PLANNER_BACKLOG (não é apagada); as demais são apagadas")
    @SuppressWarnings("unchecked")
    void deleteKeepsPlannerBacklogRows() {
        SquadIssueSnapshot backlog = SquadIssueSnapshot.builder().jiraKey("K-1").sprintId("777").sprintName("S1").inPlannerBacklog(true).build();
        SquadIssueSnapshot normal = SquadIssueSnapshot.builder().jiraKey("K-2").sprintId("777").build();
        when(issueSnapshotRepository.findBySquadIdAndJiraKeyIn(eq("SQ"), any())).thenReturn(List.of(backlog, normal));

        service.batchDeleteIssuesKeepingPlannerBacklog("SQ", List.of("K-1", "K-2"));

        assertEquals(SquadIssueSnapshot.PLANNER_BACKLOG_SPRINT_ID, backlog.getSprintId());
        assertTrue(backlog.isInPlannerBacklog());
        verify(issueSnapshotRepository).saveAll(List.of(backlog));
        verify(issueSnapshotRepository).deleteBySquadIdAndJiraKeyIn("SQ", List.of("K-2"));
    }

    @Test
    @DisplayName("sem linha de backlog entre as chaves: apaga tudo como antes")
    void deleteWithoutBacklogBehavesAsBefore() {
        when(issueSnapshotRepository.findBySquadIdAndJiraKeyIn(eq("SQ"), any())).thenReturn(List.of());
        service.batchDeleteIssuesKeepingPlannerBacklog("SQ", List.of("K-2"));
        verify(issueSnapshotRepository).deleteBySquadIdAndJiraKeyIn("SQ", List.of("K-2"));
        verify(issueSnapshotRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("upsert do sync da sprint não zera a marca do backlog nem a data do sync do planner")
    @SuppressWarnings("unchecked")
    void upsertPreservesPlannerFlag() {
        SquadIssueSnapshot prior = SquadIssueSnapshot.builder().jiraKey("K-1").inPlannerBacklog(true).plannerSyncedAt("T0").build();
        when(issueSnapshotRepository.findBySquadIdAndInPlannerBacklogTrue("SQ")).thenReturn(List.of(prior));
        when(issueSnapshotRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        SquadIssueSnapshot incoming = SquadIssueSnapshot.builder().jiraKey("K-1").sprintId("777").build();
        SquadIssueSnapshot other = SquadIssueSnapshot.builder().jiraKey("K-3").sprintId("777").build();

        service.batchUpsertIssues("SQ", List.of(incoming, other));

        assertTrue(incoming.isInPlannerBacklog());
        assertEquals("T0", incoming.getPlannerSyncedAt());
        assertFalse(other.isInPlannerBacklog());
        ArgumentCaptor<List<SquadIssueSnapshot>> cap = ArgumentCaptor.forClass(List.class);
        verify(issueSnapshotRepository).saveAll(cap.capture());
        assertEquals(2, cap.getValue().size());
    }
}
