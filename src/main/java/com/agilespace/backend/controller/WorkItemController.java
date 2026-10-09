package com.agilespace.backend.controller;

import com.agilespace.backend.domain.WorkItem;
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
    private final com.agilespace.backend.service.SquadAccessService squadAccessService;

    private static final java.util.regex.Pattern JIRA_KEY = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_]*-\\d+");

    private static void requireJiraKey(String jiraKey) {
        if (jiraKey == null || !JIRA_KEY.matcher(jiraKey).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chave da issue inválida (use o formato ABC-123).");
        }
    }

    private String resolveSquad(String squadId, String jiraKey) {
        String cleanSquad = squadId != null ? squadId.trim() : "";
        if (cleanSquad.isBlank() || "Squad Geral".equalsIgnoreCase(cleanSquad) || "Geral".equalsIgnoreCase(cleanSquad) || "Sem Time".equalsIgnoreCase(cleanSquad)) {
            if (jiraKey != null && jiraKey.contains("-")) {
                String prefix = jiraKey.split("-")[0].trim().toUpperCase();
                if (!prefix.isBlank()) {
                    return prefix;
                }
            }
        }
        if ("MISSI".equalsIgnoreCase(cleanSquad)) {
            return "DDWMISSI";
        }
        return cleanSquad;
    }

    /**
     * Mesma regra das demais telas por squad (SquadAccessService): membro real da squad, admin ou liderança da
     * tribo. Quem não pertence à squad é barrado — antes quem não tinha squad era vinculado a qualquer squad
     * só por escrever aqui.
     */
    private void requireSquadReadAccess(String squadId, HttpServletRequest request) {
        squadAccessService.requireSquadReadAccess(squadId, request);
    }

    private void requireSquadWriteAccess(String squadId, HttpServletRequest request) {
        squadAccessService.requireSquadReadAccess(squadId, request);
    }

    public record EstimateRequest(@JsonProperty("points_estimated") Double points_estimated) {}

    @PutMapping("/{squadId}/{jiraKey}/estimate")
    public ResponseEntity<Void> estimateWorkItem(
            @PathVariable String squadId,
            @PathVariable String jiraKey,
            @RequestBody EstimateRequest request,
            HttpServletRequest httpRequest) {
        requireJiraKey(jiraKey);
        String resolvedSquad = resolveSquad(squadId, jiraKey);
        requireSquadWriteAccess(resolvedSquad, httpRequest);
        workItemService.estimateWorkItem(resolvedSquad, jiraKey, request.points_estimated());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{squadId}/assignee/{accountId}")
    public ResponseEntity<List<WorkItem>> getAssignedWorkItems(
            @PathVariable String squadId,
            @PathVariable String accountId,
            HttpServletRequest httpRequest) {
        String resolvedSquad = resolveSquad(squadId, null);
        requireSquadReadAccess(resolvedSquad, httpRequest);
        List<WorkItem> workItems = workItemService.getAssignedWorkItems(resolvedSquad, accountId);
        return ResponseEntity.ok(workItems);
    }

    public record CommitRequest(@JsonProperty("sprint_id") String sprint_id) {}

    @PutMapping("/{squadId}/{jiraKey}/commit")
    public ResponseEntity<Void> commitWorkItem(
            @PathVariable String squadId,
            @PathVariable String jiraKey,
            @RequestBody CommitRequest request,
            HttpServletRequest httpRequest) {
        requireJiraKey(jiraKey);
        String resolvedSquad = resolveSquad(squadId, jiraKey);
        requireSquadWriteAccess(resolvedSquad, httpRequest);
        workItemService.commitWorkItem(resolvedSquad, jiraKey, request.sprint_id());
        return ResponseEntity.ok().build();
    }

    public record ShowcaseDecisionRequest(@JsonProperty("status") String status, @JsonProperty("feedback") String feedback) {}

    @PutMapping("/{squadId}/{jiraKey}/showcase-decision")
    public ResponseEntity<Void> showcaseDecision(
            @PathVariable String squadId,
            @PathVariable String jiraKey,
            @RequestBody ShowcaseDecisionRequest request,
            HttpServletRequest httpRequest) {
        requireJiraKey(jiraKey);
        String resolvedSquad = resolveSquad(squadId, jiraKey);
        requireSquadWriteAccess(resolvedSquad, httpRequest);
        workItemService.showcaseDecision(resolvedSquad, jiraKey, request.status(), request.feedback());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{squadId}/sprint/{sprintId}/stats")
    public ResponseEntity<java.util.Map<String, Object>> getSprintStats(
            @PathVariable String squadId,
            @PathVariable String sprintId,
            HttpServletRequest httpRequest) {
        String resolvedSquad = resolveSquad(squadId, null);
        requireSquadReadAccess(resolvedSquad, httpRequest);
        return ResponseEntity.ok(workItemService.getSprintStats(resolvedSquad, sprintId));
    }

    @GetMapping("/{squadId}")
    public ResponseEntity<List<WorkItem>> getWorkItemsBySquad(@PathVariable String squadId, HttpServletRequest httpRequest) {
        String resolvedSquad = resolveSquad(squadId, null);
        requireSquadReadAccess(resolvedSquad, httpRequest);
        return ResponseEntity.ok(workItemService.getWorkItemsBySquadId(resolvedSquad));
    }

    @GetMapping("/{squadId}/backlog-estimated")
    public ResponseEntity<List<WorkItem>> getBacklogEstimated(
            @PathVariable String squadId,
            HttpServletRequest httpRequest) {
        String resolvedSquad = resolveSquad(squadId, null);
        requireSquadReadAccess(resolvedSquad, httpRequest);
        return ResponseEntity.ok(workItemService.getBacklogEstimated(resolvedSquad));
    }
}

