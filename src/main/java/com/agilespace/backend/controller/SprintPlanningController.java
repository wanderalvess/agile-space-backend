package com.agilespace.backend.controller;

import com.agilespace.backend.domain.SprintPlanning;
import com.agilespace.backend.service.SprintPlanningService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sprint-plannings")
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class SprintPlanningController {

    private final SprintPlanningService sprintPlanningService;

    @GetMapping
    public ResponseEntity<List<SprintPlanning>> listReadyForPoker(
            @RequestParam(value = "readyForPoker", required = false, defaultValue = "false") boolean readyForPoker,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        if (!readyForPoker) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(sprintPlanningService.listReadyForPoker(limit));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SprintPlanning> getPlanner(@PathVariable("id") String id) {
        return sprintPlanningService.getPlanner(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<SprintPlanning> saveOrUpdatePlanner(@RequestBody SprintPlanning planner) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sprintPlanningService.saveOrUpdatePlanner(planner));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePlanner(@PathVariable("id") String id) {
        sprintPlanningService.deletePlanner(id);
        return ResponseEntity.noContent().build();
    }
}
