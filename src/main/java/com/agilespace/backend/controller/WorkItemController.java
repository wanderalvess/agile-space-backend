package com.agilespace.backend.controller;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.domain.WorkItem;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.WorkItemService;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import lombok.RequiredArgsConstructor;
import java.util.List;

@RestController
@RequestMapping("/api/work-items")
@RequiredArgsConstructor
public class WorkItemController {

    private final WorkItemService workItemService;
    private final UserRepository userRepository;

    /**
     * Só ADMIN/LEAD ou um membro já vinculado a esta squad (User.squadId) pode gravar
     * work_items dela. Antes disso qualquer usuário autenticado podia estimar, comprometer
     * em sprint ou dar veredito de showcase em itens de qualquer squad trocando o squadId
     * na URL. Mesma regra de SquadController.requireSquadWriteAccess.
     */
    private void requireSquadWriteAccess(String squadId, HttpServletRequest request) {
        String role = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
        if ("ADMIN".equalsIgnoreCase(role) || "LEAD".equalsIgnoreCase(role)) {
            return;
        }
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        User caller = userId != null ? userRepository.findById(userId).orElse(null) : null;
        if (caller == null || caller.getSquadId() == null || !caller.getSquadId().equalsIgnoreCase(squadId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a membros desta squad ou administradores.");
        }
    }

    public record EstimateRequest(@JsonProperty("points_estimated") Double points_estimated) {}

    @PutMapping("/{squadId}/{jiraKey}/estimate")
    public ResponseEntity<Void> estimateWorkItem(
            @PathVariable String squadId,
            @PathVariable String jiraKey,
            @RequestBody EstimateRequest request,
            HttpServletRequest httpRequest) {
        requireSquadWriteAccess(squadId, httpRequest);
        workItemService.estimateWorkItem(squadId, jiraKey, request.points_estimated());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{squadId}/assignee/{accountId}")
    public ResponseEntity<List<WorkItem>> getAssignedWorkItems(
            @PathVariable String squadId,
            @PathVariable String accountId) {
        List<WorkItem> workItems = workItemService.getAssignedWorkItems(squadId, accountId);
        return ResponseEntity.ok(workItems);
    }

    public record CommitRequest(@JsonProperty("sprint_id") String sprint_id) {}

    @PutMapping("/{squadId}/{jiraKey}/commit")
    public ResponseEntity<Void> commitWorkItem(
            @PathVariable String squadId,
            @PathVariable String jiraKey,
            @RequestBody CommitRequest request,
            HttpServletRequest httpRequest) {
        requireSquadWriteAccess(squadId, httpRequest);
        workItemService.commitWorkItem(squadId, jiraKey, request.sprint_id());
        return ResponseEntity.ok().build();
    }

    public record ShowcaseDecisionRequest(@JsonProperty("status") String status, @JsonProperty("feedback") String feedback) {}

    @PutMapping("/{squadId}/{jiraKey}/showcase-decision")
    public ResponseEntity<Void> showcaseDecision(
            @PathVariable String squadId,
            @PathVariable String jiraKey,
            @RequestBody ShowcaseDecisionRequest request,
            HttpServletRequest httpRequest) {
        requireSquadWriteAccess(squadId, httpRequest);
        workItemService.showcaseDecision(squadId, jiraKey, request.status(), request.feedback());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{squadId}/sprint/{sprintId}/stats")
    public ResponseEntity<java.util.Map<String, Object>> getSprintStats(
            @PathVariable String squadId,
            @PathVariable String sprintId) {
        return ResponseEntity.ok(workItemService.getSprintStats(squadId, sprintId));
    }

    @GetMapping("/{squadId}")
    public ResponseEntity<List<WorkItem>> getWorkItemsBySquad(@PathVariable String squadId) {
        return ResponseEntity.ok(workItemService.getWorkItemsBySquadId(squadId));
    }

    @GetMapping("/{squadId}/backlog-estimated")
    public ResponseEntity<List<WorkItem>> getBacklogEstimated(
            @PathVariable String squadId) {
        return ResponseEntity.ok(workItemService.getBacklogEstimated(squadId));
    }
}

