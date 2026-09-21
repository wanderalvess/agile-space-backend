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
    private final com.agilespace.backend.repository.ProjectMemberRoleRepository projectMemberRoleRepository;

    private boolean isLeadershipJobTitle(String jobTitle) {
        return com.agilespace.backend.security.SquadLeadership.isLeadershipJobTitle(jobTitle);
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
     * Núcleo comum de leitura E escrita: ADMIN/LEAD, ou um caller já vinculado a esta squad
     * (User.squadId/defaultProjectId, papel de projeto via ProjectMemberRoleRepository, ou
     * jobTitle de liderança quando já pertence à squad). Sem efeito colateral — nunca grava
     * nada — por isso serve tanto pra decidir leitura quanto como primeira parte de
     * requireSquadWriteAccess. Mesmo padrão de SquadController.matchesSquad.
     */
    private boolean matchesSquad(String squadId, HttpServletRequest request) {
        String role = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
        if ("ADMIN".equalsIgnoreCase(role) || "LEAD".equalsIgnoreCase(role)) {
            return true;
        }
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        User caller = userId != null ? userRepository.findById(userId).orElse(null) : null;
        if (caller == null) {
            return false;
        }

        // 0. Papel administrativo no banco
        if ("ADMIN".equalsIgnoreCase(caller.getRole()) || "LEAD".equalsIgnoreCase(caller.getRole())) {
            return true;
        }

        // 1. Checagem direta por squadId ou defaultProjectId
        boolean matches = (caller.getSquadId() != null && caller.getSquadId().equalsIgnoreCase(squadId))
                || (caller.getDefaultProjectId() != null && caller.getDefaultProjectId().equalsIgnoreCase(squadId));

        // 2. Tratamento de alias DDWMISSI <-> MISSI
        if (!matches && ("DDWMISSI".equalsIgnoreCase(squadId) || "MISSI".equalsIgnoreCase(squadId))) {
            matches = ("DDWMISSI".equalsIgnoreCase(caller.getSquadId()) || "MISSI".equalsIgnoreCase(caller.getSquadId()))
                    || ("DDWMISSI".equalsIgnoreCase(caller.getDefaultProjectId()) || "MISSI".equalsIgnoreCase(caller.getDefaultProjectId()));
        }

        // 0.1 Cargos de liderança/governança de squad — só vale se o caller já pertence a este squad
        // (senão qualquer usuário autodeclarando jobTitle de liderança ganharia acesso a squads alheios)
        if (matches && isLeadershipJobTitle(caller.getJobTitle())) {
            return true;
        }
        if (matches) {
            return true;
        }

        // 2.1 Checagem via papéis de membros de projeto (Profields / ProjectMemberRole)
        if (caller.getEmail() != null && !caller.getEmail().isBlank()) {
            matches = projectMemberRoleRepository.findByEmailIgnoreCase(caller.getEmail().trim())
                    .stream()
                    .anyMatch(r -> r.getProjectId() != null && (
                            r.getProjectId().equalsIgnoreCase(squadId) ||
                            ("DDWMISSI".equalsIgnoreCase(squadId) && "MISSI".equalsIgnoreCase(r.getProjectId())) ||
                            ("MISSI".equalsIgnoreCase(squadId) && "DDWMISSI".equalsIgnoreCase(r.getProjectId()))
                    ));
        }

        return matches;
    }

    /**
     * Leitura: qualquer membro real da squad (ou admin/liderança) — nunca auto-vincula.
     * Antes desta checagem, todo GET de /api/work-items/** exigia só autenticação, sem checar
     * pertencimento — qualquer usuário autenticado da aplicação lia work items de qualquer squad.
     */
    private void requireSquadReadAccess(String squadId, HttpServletRequest request) {
        if (!matchesSquad(squadId, request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a membros desta squad ou administradores.");
        }
    }

    /**
     * Escrita: igual à leitura, mas com um fallback a mais — em ambientes limpos onde o
     * usuário ainda não possui squad/projeto configurado, auto-associa pra permitir o
     * primeiro fluxo de sincronização (efeito colateral que uma leitura nunca deve ter).
     */
    private void requireSquadWriteAccess(String squadId, HttpServletRequest request) {
        if (matchesSquad(squadId, request)) {
            return;
        }
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        User caller = userId != null ? userRepository.findById(userId).orElse(null) : null;
        if (caller == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a membros desta squad ou administradores.");
        }

        // Auto-vinculação caso não possua squad
        boolean hasNoSquad = caller.getSquadId() == null || caller.getSquadId().isBlank() || "Sem Time".equalsIgnoreCase(caller.getSquadId().trim());
        boolean hasNoProject = caller.getDefaultProjectId() == null || caller.getDefaultProjectId().isBlank() || "Sem Time".equalsIgnoreCase(caller.getDefaultProjectId().trim());
        if (hasNoSquad) {
            caller.setSquadId(squadId);
            if (hasNoProject) {
                caller.setDefaultProjectId(squadId);
            }
            userRepository.save(caller);
            return;
        }

        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a membros desta squad ou administradores.");
    }

    public record EstimateRequest(@JsonProperty("points_estimated") Double points_estimated) {}

    @PutMapping("/{squadId}/{jiraKey}/estimate")
    public ResponseEntity<Void> estimateWorkItem(
            @PathVariable String squadId,
            @PathVariable String jiraKey,
            @RequestBody EstimateRequest request,
            HttpServletRequest httpRequest) {
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

