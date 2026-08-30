package com.agilespace.backend.controller;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.domain.WorkItem;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.WorkItemService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class WorkItemControllerTest {

    @Mock
    private WorkItemService workItemService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private WorkItemController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    private HttpServletRequest requestAs(String userId, String role) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn(userId);
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE)).thenReturn(role);
        return request;
    }

    /** Membro da squad SQ1: passa no requireSquadWriteAccess. */
    private HttpServletRequest memberOfSq1() {
        User caller = User.builder().id("u1").email("joao@empresa.com.br").squadId("SQ1").build();
        lenient().when(userRepository.findById("u1")).thenReturn(Optional.of(caller));
        return requestAs("u1", "MEMBER");
    }

    /** Membro de outra squad: deve ser barrado ao mexer em work_items da SQ1. */
    private HttpServletRequest memberOfAnotherSquad() {
        User caller = User.builder().id("u2").email("outro@empresa.com.br").squadId("SQ-OUTRA").build();
        lenient().when(userRepository.findById("u2")).thenReturn(Optional.of(caller));
        return requestAs("u2", "MEMBER");
    }

    @Test
    public void testEstimateWorkItemDelegatesPoints() {
        ResponseEntity<Void> response = controller.estimateWorkItem(
                "SQ1", "DDW-1", new WorkItemController.EstimateRequest(8.0), memberOfSq1());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(workItemService).estimateWorkItem("SQ1", "DDW-1", 8.0);
    }

    @Test
    public void testEstimateWorkItemAcceptsNullPoints() {
        ResponseEntity<Void> response = controller.estimateWorkItem(
                "SQ1", "DDW-1", new WorkItemController.EstimateRequest(null), memberOfSq1());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(workItemService).estimateWorkItem("SQ1", "DDW-1", null);
    }

    @Test
    public void testCommitWorkItemDelegatesSprintId() {
        ResponseEntity<Void> response = controller.commitWorkItem(
                "SQ1", "DDW-1", new WorkItemController.CommitRequest("SPRINT-42"), memberOfSq1());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(workItemService).commitWorkItem("SQ1", "DDW-1", "SPRINT-42");
    }

    @Test
    public void testShowcaseDecisionDelegatesStatusAndFeedback() {
        ResponseEntity<Void> response = controller.showcaseDecision(
                "SQ1", "DDW-1", new WorkItemController.ShowcaseDecisionRequest("delivered", "Aceito"), memberOfSq1());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(workItemService).showcaseDecision("SQ1", "DDW-1", "delivered", "Aceito");
    }

    @Test
    public void testGetAssignedWorkItemsReturnsList() {
        WorkItem item = WorkItem.builder().id("SQ1_DDW-1").squadId("SQ1").jiraKey("DDW-1").build();
        when(workItemService.getAssignedWorkItems("SQ1", "conta-jira")).thenReturn(List.of(item));

        ResponseEntity<List<WorkItem>> response = controller.getAssignedWorkItems("SQ1", "conta-jira");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
    }

    @Test
    public void testGetSprintStatsReturnsServiceMap() {
        Map<String, Object> stats = Map.of("velocityReal", 8.0, "previsto", 13.0, "entregue", 8.0, "carryOvers", 1);
        when(workItemService.getSprintStats("SQ1", "SPRINT-42")).thenReturn(stats);

        ResponseEntity<Map<String, Object>> response = controller.getSprintStats("SQ1", "SPRINT-42");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(8.0, response.getBody().get("velocityReal"));
    }

    @Test
    public void testGetWorkItemsBySquadReturnsList() {
        when(workItemService.getWorkItemsBySquadId("SQ1"))
                .thenReturn(List.of(WorkItem.builder().id("SQ1_DDW-1").build()));

        ResponseEntity<List<WorkItem>> response = controller.getWorkItemsBySquad("SQ1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
    }

    @Test
    public void testGetBacklogEstimatedReturnsList() {
        when(workItemService.getBacklogEstimated("SQ1"))
                .thenReturn(List.of(WorkItem.builder().id("SQ1_DDW-1").pointsEstimated(5.0).build()));

        ResponseEntity<List<WorkItem>> response = controller.getBacklogEstimated("SQ1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(5.0, response.getBody().get(0).getPointsEstimated());
    }

    @Test
    public void testGetWorkItemsBySquadEmptyList() {
        when(workItemService.getWorkItemsBySquadId("SQ-VAZIA")).thenReturn(List.of());

        ResponseEntity<List<WorkItem>> response = controller.getWorkItemsBySquad("SQ-VAZIA");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isEmpty());
    }

    // ---------- autorizacao por squad ----------

    @Test
    public void testEstimateRejectsMemberOfAnotherSquad() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.estimateWorkItem(
                "SQ1", "DDW-1", new WorkItemController.EstimateRequest(8.0), memberOfAnotherSquad()));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(workItemService);
    }

    @Test
    public void testCommitRejectsMemberOfAnotherSquad() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.commitWorkItem(
                "SQ1", "DDW-1", new WorkItemController.CommitRequest("SPRINT-42"), memberOfAnotherSquad()));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(workItemService);
    }

    @Test
    public void testShowcaseDecisionRejectsMemberOfAnotherSquad() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.showcaseDecision(
                "SQ1", "DDW-1", new WorkItemController.ShowcaseDecisionRequest("delivered", "Aceito"),
                memberOfAnotherSquad()));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(workItemService);
    }

    @Test
    public void testEstimateRejectsUserWithoutSquad() {
        User caller = User.builder().id("u3").email("sem.squad@empresa.com.br").build();
        when(userRepository.findById("u3")).thenReturn(Optional.of(caller));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.estimateWorkItem(
                "SQ1", "DDW-1", new WorkItemController.EstimateRequest(8.0), requestAs("u3", "MEMBER")));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    public void testAdminCanWriteToAnySquadWithoutUserLookup() {
        ResponseEntity<Void> response = controller.estimateWorkItem(
                "SQ-QUALQUER", "DDW-1", new WorkItemController.EstimateRequest(8.0), requestAs("admin1", "ADMIN"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(workItemService).estimateWorkItem("SQ-QUALQUER", "DDW-1", 8.0);
        verifyNoInteractions(userRepository);
    }

    @Test
    public void testLeadCanWriteToAnySquad() {
        ResponseEntity<Void> response = controller.commitWorkItem(
                "SQ-QUALQUER", "DDW-1", new WorkItemController.CommitRequest("SPRINT-42"), requestAs("lead1", "LEAD"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(workItemService).commitWorkItem("SQ-QUALQUER", "DDW-1", "SPRINT-42");
    }
}
