package com.agilespace.backend.controller;

import com.agilespace.backend.domain.ActionPlan;
import com.agilespace.backend.domain.ActionPlanTask;
import com.agilespace.backend.service.ActionPlanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/action-plans")
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class ActionPlanController {

    private final ActionPlanService actionPlanService;

    @PostMapping
    public ResponseEntity<ActionPlan> createBoard(@Valid @RequestBody ActionPlan board) {
        return ResponseEntity.status(HttpStatus.CREATED).body(actionPlanService.createBoard(board));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ActionPlan> getBoardById(@PathVariable("id") UUID id) {
        try {
            return ResponseEntity.ok(actionPlanService.getBoardById(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/participants")
    public ResponseEntity<ActionPlan> addParticipant(
            @PathVariable("id") UUID id,
            @RequestParam("participantId") String participantId) {
        try {
            return ResponseEntity.ok(actionPlanService.addParticipant(id, participantId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // Task Mappings
    @GetMapping("/{id}/tasks")
    public ResponseEntity<List<ActionPlanTask>> listTasks(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(actionPlanService.listTasks(id));
    }

    @PostMapping("/{id}/tasks")
    public ResponseEntity<ActionPlanTask> createTask(
            @PathVariable("id") UUID id,
            @Valid @RequestBody ActionPlanTask task) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(actionPlanService.createTask(id, task));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/tasks/{taskId}")
    public ResponseEntity<ActionPlanTask> updateTask(
            @PathVariable("taskId") UUID taskId,
            @Valid @RequestBody ActionPlanTask task) {
        try {
            return ResponseEntity.ok(actionPlanService.updateTask(taskId, task));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/tasks/{taskId}")
    public ResponseEntity<Void> deleteTask(@PathVariable("taskId") UUID taskId) {
        try {
            actionPlanService.deleteTask(taskId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
