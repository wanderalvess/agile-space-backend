package com.agilespace.backend.controller;

import com.agilespace.backend.domain.WorkItem;
import com.agilespace.backend.service.WorkItemService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
import java.util.List;

@RestController
@RequestMapping("/api/work-items")
@RequiredArgsConstructor
public class WorkItemController {

    private final WorkItemService workItemService;

    public record EstimateRequest(Double points_estimated) {}

    @PutMapping("/{squadId}/{jiraKey}/estimate")
    public ResponseEntity<Void> estimateWorkItem(
            @PathVariable String squadId,
            @PathVariable String jiraKey,
            @RequestBody EstimateRequest request) {
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

    public record CommitRequest(String sprint_id) {}

    @PutMapping("/{squadId}/{jiraKey}/commit")
    public ResponseEntity<Void> commitWorkItem(
            @PathVariable String squadId,
            @PathVariable String jiraKey,
            @RequestBody CommitRequest request) {
        workItemService.commitWorkItem(squadId, jiraKey, request.sprint_id());
        return ResponseEntity.ok().build();
    }

    public record ShowcaseDecisionRequest(String status, String feedback) {}

    @PutMapping("/{squadId}/{jiraKey}/showcase-decision")
    public ResponseEntity<Void> showcaseDecision(
            @PathVariable String squadId,
            @PathVariable String jiraKey,
            @RequestBody ShowcaseDecisionRequest request) {
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
