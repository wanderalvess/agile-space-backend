package com.agilespace.backend.controller;

import com.agilespace.backend.dto.SprintPlanDistributionDtos.*;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.SquadAccessService;
import com.agilespace.backend.service.sprintplan.SprintPlanDistributionService;
import com.agilespace.backend.service.sprintplan.SprintPlanDistributionService.Caller;
import com.agilespace.backend.service.sprintplan.SprintPlanDistributionService.DistributionRuleException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Distribuição do Sprint Planner (E4). Qualquer pessoa com acesso de leitura à squad lê e mexe nas PRÓPRIAS
 * subtarefas; atribuir/liberar/datar as de outra pessoa é só da liderança/ADMIN (a regra fica no serviço).
 */
@RestController
@RequestMapping("/api/squads/{squadId}/sprint-plans/{planId}/distribution")
@RequiredArgsConstructor
public class SprintPlanDistributionController {

    private final SprintPlanDistributionService service;
    private final SquadAccessService squadAccessService;

    @GetMapping
    public DistributionView get(@PathVariable String squadId, @PathVariable String planId, HttpServletRequest request) {
        return service.view(squadId, planId, caller(squadId, request));
    }

    @PostMapping("/claim")
    public DistributionView claim(@PathVariable String squadId, @PathVariable String planId,
                                  @RequestBody(required = false) ClaimRequest body, HttpServletRequest request) {
        return service.claim(squadId, planId, body, caller(squadId, request));
    }

    @DeleteMapping("/claim/{subtaskKey}")
    public DistributionView release(@PathVariable String squadId, @PathVariable String planId,
                                    @PathVariable String subtaskKey, HttpServletRequest request) {
        return service.release(squadId, planId, subtaskKey, caller(squadId, request));
    }

    @PutMapping("/dates/{subtaskKey}")
    public DistributionView dates(@PathVariable String squadId, @PathVariable String planId,
                                  @PathVariable String subtaskKey, @RequestBody(required = false) DatesRequest body,
                                  HttpServletRequest request) {
        return service.setDates(squadId, planId, subtaskKey, body, caller(squadId, request));
    }

    /** 422 da cadeia: mensagem + minStartDate. */
    @ExceptionHandler(DistributionRuleException.class)
    public ResponseEntity<Map<String, Object>> handleRule(DistributionRuleException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "UNPROCESSABLE_ENTITY");
        body.put("message", ex.getReason());
        body.put("minStartDate", ex.minStartDate() == null ? null : ex.minStartDate().toString());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    public ResponseEntity<Map<String, String>> handleConflict(Exception ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "CONFLICT",
                "message", "A distribuição foi alterada por outra pessoa. Recarregue e tente de novo."));
    }

    private Caller caller(String squadId, HttpServletRequest request) {
        squadAccessService.requireSquadReadAccess(squadId, request);
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        boolean leadership = squadAccessService.isSquadLeadership(squadId, userId,
                (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE));
        return new Caller(userId, leadership);
    }
}
