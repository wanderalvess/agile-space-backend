package com.agilespace.backend.service;

import com.agilespace.backend.domain.WorkItem;
import com.agilespace.backend.repository.WorkItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class WorkItemServiceTest {

    @Mock
    private WorkItemRepository workItemRepository;

    @InjectMocks
    private WorkItemService service;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    private WorkItem existing(String status) {
        return WorkItem.builder()
                .id("SQ1_DDW-1")
                .squadId("SQ1")
                .jiraKey("DDW-1")
                .title("Item existente")
                .status(status)
                .createdAt(LocalDateTime.now().minusDays(1))
                .build();
    }

    private WorkItem captureSaved() {
        ArgumentCaptor<WorkItem> captor = ArgumentCaptor.forClass(WorkItem.class);
        verify(workItemRepository).save(captor.capture());
        return captor.getValue();
    }

    // ---------- estimateWorkItem ----------

    @Test
    public void testEstimateCreatesItemWhenAbsentUsingCompositeId() {
        when(workItemRepository.findBySquadIdAndJiraKey("SQ1", "DDW-1")).thenReturn(Optional.empty());

        service.estimateWorkItem("SQ1", "DDW-1", 8.0);

        WorkItem saved = captureSaved();
        assertEquals("SQ1_DDW-1", saved.getId());
        assertEquals("SQ1", saved.getSquadId());
        assertEquals("DDW-1", saved.getJiraKey());
        assertEquals("DDW-1", saved.getTitle());
        assertEquals("backlog", saved.getStatus());
        assertEquals(8.0, saved.getPointsEstimated());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
    }

    @Test
    public void testEstimateUpdatesExistingItemAndPreservesStatus() {
        WorkItem item = existing("committed");
        item.setPointsEstimated(3.0);
        when(workItemRepository.findBySquadIdAndJiraKey("SQ1", "DDW-1")).thenReturn(Optional.of(item));

        service.estimateWorkItem("SQ1", "DDW-1", 13.0);

        WorkItem saved = captureSaved();
        assertEquals(13.0, saved.getPointsEstimated());
        assertEquals("committed", saved.getStatus());
        assertEquals("Item existente", saved.getTitle());
    }

    @Test
    public void testEstimateBackfillsBlankStatusToBacklog() {
        WorkItem item = existing("   ");
        when(workItemRepository.findBySquadIdAndJiraKey(anyString(), anyString())).thenReturn(Optional.of(item));

        service.estimateWorkItem("SQ1", "DDW-1", 5.0);

        assertEquals("backlog", captureSaved().getStatus());
    }

    @Test
    public void testEstimateBackfillsNullStatusToBacklog() {
        WorkItem item = existing(null);
        when(workItemRepository.findBySquadIdAndJiraKey(anyString(), anyString())).thenReturn(Optional.of(item));

        service.estimateWorkItem("SQ1", "DDW-1", 5.0);

        assertEquals("backlog", captureSaved().getStatus());
    }

    @Test
    public void testEstimateAcceptsNullPointsToClearEstimate() {
        WorkItem item = existing("backlog");
        item.setPointsEstimated(8.0);
        when(workItemRepository.findBySquadIdAndJiraKey(anyString(), anyString())).thenReturn(Optional.of(item));

        service.estimateWorkItem("SQ1", "DDW-1", null);

        assertNull(captureSaved().getPointsEstimated());
    }

    // ---------- commitWorkItem ----------

    @Test
    public void testCommitSetsStatusAndSprint() {
        WorkItem item = existing("backlog");
        when(workItemRepository.findBySquadIdAndJiraKey("SQ1", "DDW-1")).thenReturn(Optional.of(item));

        service.commitWorkItem("SQ1", "DDW-1", "SPRINT-42");

        WorkItem saved = captureSaved();
        assertEquals("committed", saved.getStatus());
        assertEquals("SPRINT-42", saved.getSprintId());
        assertNotNull(saved.getUpdatedAt());
    }

    @Test
    public void testCommitCreatesItemWhenAbsent() {
        when(workItemRepository.findBySquadIdAndJiraKey("SQ1", "DDW-9")).thenReturn(Optional.empty());

        service.commitWorkItem("SQ1", "DDW-9", "SPRINT-42");

        WorkItem saved = captureSaved();
        assertEquals("SQ1_DDW-9", saved.getId());
        assertEquals("committed", saved.getStatus());
        assertEquals("SPRINT-42", saved.getSprintId());
    }

    // ---------- showcaseDecision ----------

    @Test
    public void testShowcaseDecisionRecordsVerdictFeedbackAndTimestamp() {
        WorkItem item = existing("committed");
        when(workItemRepository.findBySquadIdAndJiraKey("SQ1", "DDW-1")).thenReturn(Optional.of(item));

        service.showcaseDecision("SQ1", "DDW-1", "delivered", "Aceito pelo PO");

        WorkItem saved = captureSaved();
        assertEquals("delivered", saved.getStatus());
        assertEquals("Aceito pelo PO", saved.getDecisionFeedback());
        assertNotNull(saved.getDecidedAt());
        assertNotNull(saved.getUpdatedAt());
    }

    @Test
    public void testShowcaseDecisionRejectedKeepsFeedback() {
        WorkItem item = existing("committed");
        when(workItemRepository.findBySquadIdAndJiraKey(anyString(), anyString())).thenReturn(Optional.of(item));

        service.showcaseDecision("SQ1", "DDW-1", "rejected", "Criterio de aceite nao atendido");

        WorkItem saved = captureSaved();
        assertEquals("rejected", saved.getStatus());
        assertEquals("Criterio de aceite nao atendido", saved.getDecisionFeedback());
    }

    // ---------- getBacklogEstimated ----------

    @Test
    public void testGetBacklogEstimatedFiltersUnestimatedAndZeroPointItems() {
        WorkItem estimated = existing("backlog");
        estimated.setJiraKey("DDW-1");
        estimated.setPointsEstimated(5.0);

        WorkItem zeroPoints = existing("backlog");
        zeroPoints.setJiraKey("DDW-2");
        zeroPoints.setPointsEstimated(0.0);

        WorkItem noPoints = existing("backlog");
        noPoints.setJiraKey("DDW-3");

        when(workItemRepository.findBySquadIdAndStatus("SQ1", "backlog"))
                .thenReturn(List.of(estimated, zeroPoints, noPoints));

        List<WorkItem> result = service.getBacklogEstimated("SQ1");

        assertEquals(1, result.size());
        assertEquals("DDW-1", result.get(0).getJiraKey());
    }

    @Test
    public void testGetBacklogEstimatedEmptyWhenNothingInBacklog() {
        when(workItemRepository.findBySquadIdAndStatus("SQ1", "backlog")).thenReturn(List.of());

        assertTrue(service.getBacklogEstimated("SQ1").isEmpty());
    }

    // ---------- getSprintStats ----------

    private WorkItem item(String key, String status, Double points) {
        WorkItem w = existing(status);
        w.setJiraKey(key);
        w.setPointsEstimated(points);
        return w;
    }

    @Test
    public void testGetSprintStatsSumsDeliveredAsVelocityAndCountsCarryOvers() {
        when(workItemRepository.findBySquadIdAndSprintId("SQ1", "SPRINT-42")).thenReturn(List.of(
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
        when(workItemRepository.findBySquadIdAndSprintId("SQ1", "SPRINT-42")).thenReturn(List.of(
                item("DDW-1", "delivered", null),
                item("DDW-2", "delivered", 5.0)));

        Map<String, Object> stats = service.getSprintStats("SQ1", "SPRINT-42");

        assertEquals(5.0, stats.get("velocityReal"));
        assertEquals(5.0, stats.get("previsto"));
    }

    @Test
    public void testGetSprintStatsEmptySprintReturnsZeroes() {
        when(workItemRepository.findBySquadIdAndSprintId("SQ1", "SPRINT-42")).thenReturn(List.of());

        Map<String, Object> stats = service.getSprintStats("SQ1", "SPRINT-42");

        assertEquals(0.0, stats.get("velocityReal"));
        assertEquals(0.0, stats.get("previsto"));
        assertEquals(0, stats.get("carryOvers"));
    }

    // ---------- getAssignedWorkItems ----------

    @Test
    public void testGetAssignedWorkItemsTrimsIdentifier() {
        when(workItemRepository.findBySquadIdAndUserIdentifier("SQ1", "conta-jira")).thenReturn(List.of(existing("committed")));

        assertEquals(1, service.getAssignedWorkItems("SQ1", "  conta-jira  ").size());
        verify(workItemRepository).findBySquadIdAndUserIdentifier("SQ1", "conta-jira");
    }

    @Test
    public void testGetAssignedWorkItemsReturnsEmptyForNullOrBlankIdentifier() {
        assertTrue(service.getAssignedWorkItems("SQ1", null).isEmpty());
        assertTrue(service.getAssignedWorkItems("SQ1", "   ").isEmpty());
        verify(workItemRepository, never()).findBySquadIdAndUserIdentifier(anyString(), anyString());
    }

    // ---------- getWorkItemsBySquadId ----------

    @Test
    public void testGetWorkItemsBySquadIdDelegatesToRepository() {
        when(workItemRepository.findBySquadId("SQ1")).thenReturn(List.of(existing("backlog")));

        assertEquals(1, service.getWorkItemsBySquadId("SQ1").size());
        verify(workItemRepository).findBySquadId("SQ1");
    }
}
