package com.agilespace.backend.controller;

import com.agilespace.backend.domain.JiraIssueTypeCache;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.PlannerBacklogService;
import com.agilespace.backend.service.PlannerBacklogSyncService;
import com.agilespace.backend.service.SquadAccessService;
import com.agilespace.backend.service.SquadSyncGuard;
import com.agilespace.backend.service.SubtaskTypeMappingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Sprint Planner (E1b): backlog sincronizado do Jira e mapeamento tipo de subtarefa -> classe.
 * Leitura: membro da squad. Sync e edição do mapeamento: liderança da squad ou ADMIN.
 * Só o POST de sync chama o Jira; as leituras vêm do snapshot.
 */
@RestController
@RequestMapping("/api/squads/{squadId}")
@RequiredArgsConstructor
public class SprintPlannerBacklogController {

    private final SquadAccessService squadAccessService;
    private final SquadSyncGuard squadSyncGuard;
    private final PlannerBacklogSyncService syncService;
    private final PlannerBacklogService backlogService;
    private final SubtaskTypeMappingService mappingService;

    public record SubtaskTypesUpdate(String sprintKey, List<SubtaskTypeMappingService.UpdateRequest> types) {}

    private void requireLeadership(String squadId, HttpServletRequest request) {
        if (!squadAccessService.canManageSquad(squadId, request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Só a liderança da squad (Agile Master, People Lead, Tech Lead, PO…) ou um administrador pode fazer isso.");
        }
    }

    @PostMapping("/sprint-plans/backlog/sync")
    public ResponseEntity<PlannerBacklogSyncService.SyncResult> syncBacklog(@PathVariable String squadId, HttpServletRequest request) {
        requireLeadership(squadId, request);
        // Mesmo guarda do sync da sprint: os dois gravam em squad_issue_snapshots e não podem rodar juntos.
        if (!squadSyncGuard.tryAcquire(squadId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma sincronização em andamento para esta squad.");
        }
        try {
            String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
            return ResponseEntity.ok(syncService.sync(squadId, userId));
        } finally {
            squadSyncGuard.release(squadId);
        }
    }

    @GetMapping("/sprint-plans/backlog")
    public ResponseEntity<PlannerBacklogService.BacklogView> getBacklog(
            @PathVariable String squadId,
            @RequestParam(required = false) String sprintKey,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "false") boolean onlyWithHours,
            HttpServletRequest request) {
        squadAccessService.requireSquadReadAccess(squadId, request);
        return ResponseEntity.ok(backlogService.view(squadId, sprintKey, q, onlyWithHours));
    }

    @GetMapping("/subtask-types")
    public ResponseEntity<Map<String, Object>> getSubtaskTypes(
            @PathVariable String squadId,
            @RequestParam(required = false) String sprintKey,
            HttpServletRequest request) {
        squadAccessService.requireSquadReadAccess(squadId, request);
        String key = SubtaskTypeMappingService.normalizeSprintKey(sprintKey);
        return ResponseEntity.ok(Map.of(
                "sprintKey", key,
                "canEdit", squadAccessService.canManageSquad(squadId, request),
                "types", mappingService.resolve(squadId, key, List.of())));
    }

    @PutMapping("/subtask-types")
    public ResponseEntity<Map<String, Object>> putSubtaskTypes(
            @PathVariable String squadId,
            @RequestBody SubtaskTypesUpdate body,
            HttpServletRequest request) {
        requireLeadership(squadId, request);
        if (body == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Corpo vazio.");
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        String key = SubtaskTypeMappingService.normalizeSprintKey(body.sprintKey());
        return ResponseEntity.ok(Map.of(
                "sprintKey", key,
                "canEdit", true,
                "types", mappingService.update(squadId, key, body.types(), userId)));
    }

    @GetMapping("/subtask-types/known")
    public ResponseEntity<List<JiraIssueTypeCache>> getKnownTypes(@PathVariable String squadId, HttpServletRequest request) {
        squadAccessService.requireSquadReadAccess(squadId, request);
        return ResponseEntity.ok(mappingService.knownTypes(squadId));
    }
}
