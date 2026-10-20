package com.agilespace.backend.controller;

import com.agilespace.backend.dto.SprintPlanDtos.*;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.SquadAccessService;
import com.agilespace.backend.service.sprintplan.SprintPlanScopeService;
import com.agilespace.backend.service.sprintplan.SprintPlanService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Sprint Planner (E1a): plano, capacidade, ausências, feriados e reserva. Membro da squad lê; liderança da squad
 * (inclui AM) e ADMIN editam. Envio ao Jira responde 501 até a E6.
 */
@RestController
@RequestMapping("/api/squads/{squadId}/sprint-plans")
@RequiredArgsConstructor
public class SprintPlanController {

    private final SprintPlanService service;
    private final SprintPlanScopeService scopeService;
    private final SquadAccessService squadAccessService;

    @GetMapping
    public List<PlanSummary> list(@PathVariable String squadId, HttpServletRequest request) {
        squadAccessService.requireSquadReadAccess(squadId, request);
        return service.list(squadId);
    }

    @PostMapping
    public ResponseEntity<PlanView> create(@PathVariable String squadId, @RequestBody(required = false) CreatePlanRequest body,
                                           HttpServletRequest request) {
        requireLeadership(squadId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(squadId, body, userId(request)));
    }

    @GetMapping("/{planId}")
    public PlanView get(@PathVariable String squadId, @PathVariable String planId, HttpServletRequest request) {
        squadAccessService.requireSquadReadAccess(squadId, request);
        return service.view(squadId, planId, canEdit(squadId, request), userId(request));
    }

    @PatchMapping("/{planId}")
    public PlanView patch(@PathVariable String squadId, @PathVariable String planId,
                          @RequestBody(required = false) PatchPlanRequest body, HttpServletRequest request) {
        requireLeadership(squadId, request);
        return service.patch(squadId, planId, body, userId(request));
    }

    @PostMapping("/{planId}/status")
    public PlanView status(@PathVariable String squadId, @PathVariable String planId,
                           @RequestBody(required = false) StatusRequest body, HttpServletRequest request) {
        requireLeadership(squadId, request);
        return service.changeStatus(squadId, planId, body, userId(request));
    }

    @DeleteMapping("/{planId}")
    public ResponseEntity<Void> delete(@PathVariable String squadId, @PathVariable String planId, HttpServletRequest request) {
        requireLeadership(squadId, request);
        service.delete(squadId, planId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{planId}/capacity")
    public CapacityView capacity(@PathVariable String squadId, @PathVariable String planId, HttpServletRequest request) {
        squadAccessService.requireSquadReadAccess(squadId, request);
        return service.capacity(squadId, planId);
    }

    @PutMapping("/{planId}/capacity/{accountId}")
    public CapacityRow updatePerson(@PathVariable String squadId, @PathVariable String planId, @PathVariable String accountId,
                                    @RequestBody(required = false) CapacityUpdateRequest body, HttpServletRequest request) {
        requireLeadership(squadId, request);
        return service.updatePerson(squadId, planId, accountId, body);
    }

    @PostMapping("/{planId}/absences")
    public ResponseEntity<AbsenceView> addAbsence(@PathVariable String squadId, @PathVariable String planId,
                                                  @RequestBody(required = false) AbsenceRequest body, HttpServletRequest request) {
        requireLeadership(squadId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addAbsence(squadId, planId, body, userId(request)));
    }

    @DeleteMapping("/{planId}/absences/{absenceId}")
    public ResponseEntity<Void> deleteAbsence(@PathVariable String squadId, @PathVariable String planId,
                                              @PathVariable String absenceId, HttpServletRequest request) {
        requireLeadership(squadId, request);
        service.deleteAbsence(squadId, planId, absenceId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{planId}/holidays")
    public List<HolidayDto> holidays(@PathVariable String squadId, @PathVariable String planId,
                                     @RequestBody(required = false) List<HolidayDto> body, HttpServletRequest request) {
        requireLeadership(squadId, request);
        return service.replaceHolidays(squadId, planId, body);
    }

    @PutMapping("/{planId}/reserve-types")
    public List<ReserveTypeView> reserveTypes(@PathVariable String squadId, @PathVariable String planId,
                                              @RequestBody(required = false) List<ReserveTypeRequest> body,
                                              HttpServletRequest request) {
        requireLeadership(squadId, request);
        return service.replaceReserveTypes(squadId, planId, body);
    }

    // ---- Escopo (E3) ----

    @GetMapping("/{planId}/items")
    public ScopeView items(@PathVariable String squadId, @PathVariable String planId, HttpServletRequest request) {
        squadAccessService.requireSquadReadAccess(squadId, request);
        return scopeService.view(squadId, planId, canEdit(squadId, request));
    }

    @PostMapping("/{planId}/items")
    public ResponseEntity<ScopeView> addItems(@PathVariable String squadId, @PathVariable String planId,
                                              @RequestBody(required = false) AddItemsRequest body, HttpServletRequest request) {
        requireLeadership(squadId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(scopeService.addItems(squadId, planId, body, userId(request), true));
    }

    @DeleteMapping("/{planId}/items/{itemId}")
    public ResponseEntity<Void> removeItem(@PathVariable String squadId, @PathVariable String planId,
                                           @PathVariable String itemId, HttpServletRequest request) {
        requireLeadership(squadId, request);
        scopeService.removeItem(squadId, planId, itemId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{planId}/items/order")
    public ScopeView reorderItems(@PathVariable String squadId, @PathVariable String planId,
                                  @RequestBody(required = false) ReorderItemsRequest body, HttpServletRequest request) {
        requireLeadership(squadId, request);
        return scopeService.reorder(squadId, planId, body, true);
    }

    /** E6: gravação no Jira ainda não existe. */
    @PostMapping("/{planId}/send-to-jira")
    public ResponseEntity<Map<String, String>> sendToJira(@PathVariable String squadId, @PathVariable String planId,
                                                          HttpServletRequest request) {
        requireLeadership(squadId, request);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of(
                "error", "NOT_IMPLEMENTED",
                "message", "O envio ao Jira ainda não está disponível."));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "CONFLICT",
                "message", "O plano foi alterado por outra pessoa. Recarregue e tente de novo."));
    }

    // ---- Autorização ----

    private boolean canEdit(String squadId, HttpServletRequest request) {
        return squadAccessService.isSquadLeadership(squadId, userId(request),
                (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE));
    }

    private void requireLeadership(String squadId, HttpServletRequest request) {
        if (!canEdit(squadId, request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Só a liderança da squad ou um administrador pode alterar o plano da sprint.");
        }
    }

    private static String userId(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
    }
}
