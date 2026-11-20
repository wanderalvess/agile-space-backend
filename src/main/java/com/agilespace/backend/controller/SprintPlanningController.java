package com.agilespace.backend.controller;

import com.agilespace.backend.domain.SprintPlanning;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.SprintPlanningService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @deprecated API antiga do planejamento (V19, sem squad), substituída pelo Sprint Planner
 * ({@code /api/squads/{squadId}/sprint-plans}). Tabelas e dados continuam; nada é removido nesta rodada. Cada uso
 * gera um log de aviso para medir se ainda há consumidores antes da remoção.
 */
@Deprecated
@RestController
@RequestMapping("/api/sprint-plannings")
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class SprintPlanningController {

    private final SprintPlanningService sprintPlanningService;

    @ModelAttribute
    void warnDeprecatedUse(HttpServletRequest request) {
        log.warn("[DEPRECATED] {} {} usa a API antiga /api/sprint-plannings; use /api/squads/{{squadId}}/sprint-plans.",
                request.getMethod(), request.getRequestURI());
    }

    @GetMapping
    public ResponseEntity<List<SprintPlanning>> listReadyForPoker(
            @RequestParam(value = "readyForPoker", required = false, defaultValue = "false") boolean readyForPoker,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit,
            HttpServletRequest request) {
        if (!readyForPoker) {
            return ResponseEntity.ok(List.of());
        }
        String callerId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        return ResponseEntity.ok(sprintPlanningService.listReadyForPoker(limit, callerId, isAdmin(request)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SprintPlanning> getPlanner(@PathVariable("id") String id) {
        return sprintPlanningService.getPlanner(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<SprintPlanning> saveOrUpdatePlanner(@RequestBody SprintPlanning planner, HttpServletRequest request) {
        String callerId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        return ResponseEntity.status(HttpStatus.CREATED).body(sprintPlanningService.saveOrUpdatePlanner(planner, callerId, isAdmin(request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePlanner(@PathVariable("id") String id, HttpServletRequest request) {
        String callerId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        sprintPlanningService.deletePlanner(id, callerId, isAdmin(request));
        return ResponseEntity.noContent().build();
    }

    private boolean isAdmin(HttpServletRequest request) {
        return "ADMIN".equalsIgnoreCase((String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE));
    }
}
