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

    private static final java.util.Set<String> LEADERSHIP_JOB_TITLES = java.util.Set.of(
            "tech lead", "scrum master", "agile master", "product owner",
            "people lead", "tribe lead", "agile coach", "sme", "admin", "lead"
    );

    private boolean isLeadershipJobTitle(String jobTitle) {
        if (jobTitle == null || jobTitle.isBlank()) return false;
        return LEADERSHIP_JOB_TITLES.contains(jobTitle.trim().toLowerCase());
    }

    /**
     * Só ADMIN/LEAD ou um membro já vinculado a esta squad (User.squadId, defaultProjectId, etc.)
     * pode gravar work_items dela. Mesma regra de SquadController.requireSquadWriteAccess.
     */
    private void requireSquadWriteAccess(String squadId, HttpServletRequest request) {
        String role = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
        if ("ADMIN".equalsIgnoreCase(role) || "LEAD".equalsIgnoreCase(role)) {
            return;
        }
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        User caller = userId != null ? userRepository.findById(userId).orElse(null) : null;
        if (caller == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a membros desta squad ou administradores.");
        }

        // 0. Papel administrativo no banco
        if ("ADMIN".equalsIgnoreCase(caller.getRole()) || "LEAD".equalsIgnoreCase(caller.getRole())) {
            return;
        }

        // 0.1 Cargos de liderança/governança
        if (isLeadershipJobTitle(caller.getJobTitle())) {
            return;
        }

        // 1. Checagem direta por squadId ou defaultProjectId
        boolean matches = (caller.getSquadId() != null && caller.getSquadId().equalsIgnoreCase(squadId))
                || (caller.getDefaultProjectId() != null && caller.getDefaultProjectId().equalsIgnoreCase(squadId));

        // 2. Tratamento de alias DDWMISSI <-> MISSI
        if (!matches && ("DDWMISSI".equalsIgnoreCase(squadId) || "MISSI".equalsIgnoreCase(squadId))) {
            matches = ("DDWMISSI".equalsIgnoreCase(caller.getSquadId()) || "MISSI".equalsIgnoreCase(caller.getSquadId()))
                    || ("DDWMISSI".equalsIgnoreCase(caller.getDefaultProjectId()) || "MISSI".equalsIgnoreCase(caller.getDefaultProjectId()));
        }

        // 3. Auto-vinculação caso não possua squad
        boolean hasNoSquad = caller.getSquadId() == null || caller.getSquadId().isBlank() || "Sem Time".equalsIgnoreCase(caller.getSquadId().trim());
        boolean hasNoProject = caller.getDefaultProjectId() == null || caller.getDefaultProjectId().isBlank() || "Sem Time".equalsIgnoreCase(caller.getDefaultProjectId().trim());
        if (!matches && hasNoSquad) {
            caller.setSquadId(squadId);
            if (hasNoProject) {
                caller.setDefaultProjectId(squadId);
            }
            userRepository.save(caller);
            matches = true;
        }

        if (!matches) {
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

