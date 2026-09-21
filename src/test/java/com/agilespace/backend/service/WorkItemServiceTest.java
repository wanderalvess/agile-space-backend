package com.agilespace.backend.service;

import com.agilespace.backend.domain.SquadIssueSnapshot;
import com.agilespace.backend.domain.WorkItem;
import com.agilespace.backend.repository.SquadIssueSnapshotRepository;
import com.agilespace.backend.repository.SquadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class WorkItemServiceTest {

    @Mock
    private SquadIssueSnapshotRepository issueSnapshotRepository;

    @Mock
    private SquadRepository squadRepository;

    @InjectMocks
    private WorkItemService service;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    private SquadIssueSnapshot existing(String ceremonyStatus) {
        return SquadIssueSnapshot.builder()
                .dbId("SQ1_DDW-1")
                .squadId("SQ1")
                .jiraKey("DDW-1")
                .ceremonyStatus(ceremonyStatus)
                .build();
    }

    private SquadIssueSnapshot captureSaved() {
        ArgumentCaptor<SquadIssueSnapshot> captor = ArgumentCaptor.forClass(SquadIssueSnapshot.class);
        verify(issueSnapshotRepository).save(captor.capture());
        return captor.getValue();
    }

    // ---------- estimateWorkItem ----------

    @Test
    public void testEstimateCreatesItemWhenAbsentUsingCompositeId() {
        when(issueSnapshotRepository.findBySquadIdAndJiraKey("SQ1", "DDW-1")).thenReturn(Optional.empty());

        service.estimateWorkItem("SQ1", "DDW-1", 8.0);

        SquadIssueSnapshot saved = captureSaved();
        assertEquals("SQ1_DDW-1", saved.getDbId());
        assertEquals("SQ1", saved.getSquadId());
        assertEquals("DDW-1", saved.getJiraKey());
        assertEquals("backlog", saved.getCeremonyStatus());
        assertEquals(8.0, saved.getPointsEstimated());
    }

    @Test
    public void testEstimateUpdatesExistingItemAndPreservesCeremonyStatus() {
        SquadIssueSnapshot item = existing("committed");
        item.setPointsEstimated(3.0);
        when(issueSnapshotRepository.findBySquadIdAndJiraKey("SQ1", "DDW-1")).thenReturn(Optional.of(item));

        service.estimateWorkItem("SQ1", "DDW-1", 13.0);

        SquadIssueSnapshot saved = captureSaved();
        assertEquals(13.0, saved.getPointsEstimated());
        assertEquals("committed", saved.getCeremonyStatus());
    }

    @Test
    public void testEstimateBackfillsBlankCeremonyStatusToBacklog() {
        SquadIssueSnapshot item = existing("   ");
        when(issueSnapshotRepository.findBySquadIdAndJiraKey(anyString(), anyString())).thenReturn(Optional.of(item));

        service.estimateWorkItem("SQ1", "DDW-1", 5.0);

        assertEquals("backlog", captureSaved().getCeremonyStatus());
    }

    @Test
    public void testEstimateBackfillsNullCeremonyStatusToBacklog() {
        SquadIssueSnapshot item = existing(null);
        when(issueSnapshotRepository.findBySquadIdAndJiraKey(anyString(), anyString())).thenReturn(Optional.of(item));

        service.estimateWorkItem("SQ1", "DDW-1", 5.0);

        assertEquals("backlog", captureSaved().getCeremonyStatus());
    }

    @Test
    public void testEstimateAcceptsNullPointsToClearEstimate() {
        SquadIssueSnapshot item = existing("backlog");
        item.setPointsEstimated(8.0);
        when(issueSnapshotRepository.findBySquadIdAndJiraKey(anyString(), anyString())).thenReturn(Optional.of(item));

        service.estimateWorkItem("SQ1", "DDW-1", null);

        assertNull(captureSaved().getPointsEstimated());
    }

    @Test
    public void testEstimatePreservesRealJiraFieldsAlreadySyncedOnTheSnapshot() {
        // A grande vantagem de gravar em SquadIssueSnapshot em vez de work_items: campos
        // populados pelo sync real do Squad (type/status do Jira) não somem quando o Poker
        // grava a estimativa por cima da mesma linha.
        SquadIssueSnapshot item = existing("backlog");
        item.setType("Story");
        item.setStatus("In Progress");
        item.setAssigneeName("Fulano");
        when(issueSnapshotRepository.findBySquadIdAndJiraKey("SQ1", "DDW-1")).thenReturn(Optional.of(item));

        service.estimateWorkItem("SQ1", "DDW-1", 8.0);

        SquadIssueSnapshot saved = captureSaved();
        assertEquals("Story", saved.getType());
        assertEquals("In Progress", saved.getStatus());
        assertEquals("Fulano", saved.getAssigneeName());
    }

    // ---------- commitWorkItem ----------

    @Test
    public void testCommitSetsCeremonyStatusAndSprint() {
        SquadIssueSnapshot item = existing("backlog");
        when(issueSnapshotRepository.findBySquadIdAndJiraKey("SQ1", "DDW-1")).thenReturn(Optional.of(item));

        service.commitWorkItem("SQ1", "DDW-1", "SPRINT-42");

        SquadIssueSnapshot saved = captureSaved();
        assertEquals("committed", saved.getCeremonyStatus());
        assertEquals("SPRINT-42", saved.getSprintId());
    }

    @Test
    public void testCommitCreatesItemWhenAbsent() {
        when(issueSnapshotRepository.findBySquadIdAndJiraKey("SQ1", "DDW-9")).thenReturn(Optional.empty());

        service.commitWorkItem("SQ1", "DDW-9", "SPRINT-42");

        SquadIssueSnapshot saved = captureSaved();
        assertEquals("SQ1_DDW-9", saved.getDbId());
        assertEquals("committed", saved.getCeremonyStatus());
        assertEquals("SPRINT-42", saved.getSprintId());
    }

    // ---------- showcaseDecision ----------

    @Test
    public void testShowcaseDecisionRecordsVerdictFeedbackAndTimestamp() {
        SquadIssueSnapshot item = existing("committed");
        when(issueSnapshotRepository.findBySquadIdAndJiraKey("SQ1", "DDW-1")).thenReturn(Optional.of(item));

        service.showcaseDecision("SQ1", "DDW-1", "delivered", "Aceito pelo PO");

        SquadIssueSnapshot saved = captureSaved();
        assertEquals("delivered", saved.getCeremonyStatus());
        assertEquals("Aceito pelo PO", saved.getDecisionFeedback());
        assertNotNull(saved.getDecidedAt());
    }

    @Test
    public void testShowcaseDecisionRejectedKeepsFeedback() {
        SquadIssueSnapshot item = existing("committed");
        when(issueSnapshotRepository.findBySquadIdAndJiraKey(anyString(), anyString())).thenReturn(Optional.of(item));

        service.showcaseDecision("SQ1", "DDW-1", "rejected", "Criterio de aceite nao atendido");

        SquadIssueSnapshot saved = captureSaved();
        assertEquals("rejected", saved.getCeremonyStatus());
        assertEquals("Criterio de aceite nao atendido", saved.getDecisionFeedback());
    }

    // ---------- getBacklogEstimated ----------

    @Test
    public void testGetBacklogEstimatedFiltersUnestimatedAndZeroPointItems() {
        SquadIssueSnapshot estimated = existing("backlog");
        estimated.setJiraKey("DDW-1");
        estimated.setPointsEstimated(5.0);

        SquadIssueSnapshot zeroPoints = existing("backlog");
        zeroPoints.setJiraKey("DDW-2");
        zeroPoints.setPointsEstimated(0.0);

        SquadIssueSnapshot noPoints = existing("backlog");
        noPoints.setJiraKey("DDW-3");

        when(issueSnapshotRepository.findBySquadIdAndCeremonyStatus("SQ1", "backlog"))
                .thenReturn(List.of(estimated, zeroPoints, noPoints));

        List<WorkItem> result = service.getBacklogEstimated("SQ1");

        assertEquals(1, result.size());
        assertEquals("DDW-1", result.get(0).getJiraKey());
    }

    @Test
    public void testGetBacklogEstimatedEmptyWhenNothingInBacklog() {
        when(issueSnapshotRepository.findBySquadIdAndCeremonyStatus("SQ1", "backlog")).thenReturn(List.of());

        assertTrue(service.getBacklogEstimated("SQ1").isEmpty());
    }

    // ---------- getSprintStats ----------

    private SquadIssueSnapshot item(String key, String ceremonyStatus, Double points) {
        SquadIssueSnapshot s = existing(ceremonyStatus);
        s.setJiraKey(key);
        s.setPointsEstimated(points);
        return s;
    }

    @Test
    public void testGetSprintStatsSumsDeliveredAsVelocityAndCountsCarryOvers() {
        when(issueSnapshotRepository.findBySquadIdAndSprintId("SQ1", "SPRINT-42")).thenReturn(List.of(
                item("DDW-1", "delivered", 5.0),
                item("DDW-2", "delivered", 3.0),
                item("DDW-3", "carried_over", 8.0),
                item("DDW-4", "committed", 2.0)));

        Map<String, Object> stats = service.getSprintStats("SQ1", "SPRINT-42");

        assertEquals(8.0, stats.get("velocityReal"));
        assertEquals(8.0, stats.get("entregue"));
        assertEquals(18.0, stats.get("previsto"));
        assertEquals(1, stats.get("carryOvers"));
    }

    @Test
    public void testGetSprintStatsTreatsUnestimatedItemsAsZero() {
        when(issueSnapshotRepository.findBySquadIdAndSprintId("SQ1", "SPRINT-42")).thenReturn(List.of(
                item("DDW-1", "delivered", null),
                item("DDW-2", "delivered", 5.0)));

        Map<String, Object> stats = service.getSprintStats("SQ1", "SPRINT-42");

        assertEquals(5.0, stats.get("velocityReal"));
        assertEquals(5.0, stats.get("previsto"));
    }

    @Test
    public void testGetSprintStatsEmptySprintReturnsZeroes() {
        when(issueSnapshotRepository.findBySquadIdAndSprintId("SQ1", "SPRINT-42")).thenReturn(List.of());

        Map<String, Object> stats = service.getSprintStats("SQ1", "SPRINT-42");

        assertEquals(0.0, stats.get("velocityReal"));
        assertEquals(0.0, stats.get("previsto"));
        assertEquals(0, stats.get("carryOvers"));
    }

    @Test
    public void testGetSprintStatsWithActiveSprintIdResolvesSquadActiveSprint() {
        com.agilespace.backend.domain.Squad squad = new com.agilespace.backend.domain.Squad();
        squad.setId("SQ1");
        squad.setActiveSprintId("SPRINT-99");
        when(squadRepository.findById("SQ1")).thenReturn(Optional.of(squad));
        when(issueSnapshotRepository.findBySquadIdAndSprintId("SQ1", "SPRINT-99")).thenReturn(List.of(
                item("DDW-1", "delivered", 8.0),
                item("DDW-2", "committed", 3.0)));

        Map<String, Object> stats = service.getSprintStats("SQ1", "active");

        assertEquals(8.0, stats.get("velocityReal"));
        assertEquals(11.0, stats.get("previsto"));
        assertEquals(0, stats.get("carryOvers"));
    }

    @Test
    public void testGetSprintStatsWithActiveSprintFallbackToActiveWorkItems() {
        when(squadRepository.findById("SQ1")).thenReturn(Optional.empty());
        when(issueSnapshotRepository.findBySquadId("SQ1")).thenReturn(List.of(
                item("DDW-1", "delivered", 5.0),
                item("DDW-2", "backlog", 13.0),
                item("DDW-3", "carried_over", 2.0)));

        Map<String, Object> stats = service.getSprintStats("SQ1", "active");

        assertEquals(5.0, stats.get("velocityReal"));
        assertEquals(7.0, stats.get("previsto"));
        assertEquals(1, stats.get("carryOvers"));
    }

    // ---------- getAssignedWorkItems ----------

    @Test
    public void testGetAssignedWorkItemsTrimsIdentifier() {
        when(issueSnapshotRepository.findBySquadIdAndAssigneeIdentifier("SQ1", "conta-jira"))
                .thenReturn(List.of(existing("committed")));

        assertEquals(1, service.getAssignedWorkItems("SQ1", "  conta-jira  ").size());
        verify(issueSnapshotRepository).findBySquadIdAndAssigneeIdentifier("SQ1", "conta-jira");
    }

    @Test
    public void testGetAssignedWorkItemsReturnsEmptyForNullOrBlankIdentifier() {
        assertTrue(service.getAssignedWorkItems("SQ1", null).isEmpty());
        assertTrue(service.getAssignedWorkItems("SQ1", "   ").isEmpty());
        verify(issueSnapshotRepository, never()).findBySquadIdAndAssigneeIdentifier(anyString(), anyString());
    }

    // ---------- getWorkItemsBySquadId ----------

    @Test
    public void testGetWorkItemsBySquadIdDelegatesToRepository() {
        when(issueSnapshotRepository.findBySquadId("SQ1")).thenReturn(List.of(existing("backlog")));

        assertEquals(1, service.getWorkItemsBySquadId("SQ1").size());
        verify(issueSnapshotRepository).findBySquadId("SQ1");
    }
}
