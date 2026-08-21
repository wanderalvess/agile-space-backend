package com.agilespace.backend.controller;

import com.agilespace.backend.domain.SprintPlanning;
import com.agilespace.backend.service.SprintPlanningService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sprint-plannings")
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class SprintPlanningController {

    private final SprintPlanningService sprintPlanningService;

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
