package com.agilespace.backend.controller;

import com.agilespace.backend.domain.*;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.SquadService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/squads")
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class SquadController {

    private final SquadService squadService;
    private final UserRepository userRepository;
    private final com.agilespace.backend.service.UserProjectResolverService userProjectResolverService;

    private static final java.util.Set<String> LEADERSHIP_JOB_TITLES = java.util.Set.of(
            "tech lead", "scrum master", "agile master", "product owner",
            "people lead", "tribe lead", "agile coach", "sme", "admin", "lead"
    );

    private boolean isLeadershipJobTitle(String jobTitle) {
        if (jobTitle == null || jobTitle.isBlank()) return false;
        return LEADERSHIP_JOB_TITLES.contains(jobTitle.trim().toLowerCase());
    }

    /**
     * Só ADMIN/LEAD ou um membro vinculado a esta squad (User.squadId, defaultProjectId, papel em projeto
     * ou membro no roster squad_members) pode gravar dados nela. Em ambientes limpos onde o usuário ainda
     * não possui squad/projeto configurado, auto-associa para permitir o primeiro fluxo de sincronização.
     */
    private void requireSquadWriteAccess(String squadId, HttpServletRequest request) {
        String role = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
        if ("ADMIN".equalsIgnoreCase(role) || "LEAD".equalsIgnoreCase(role)) {
            return;
        }
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        User caller = userId != null ? userRepository.findById(userId).orElse(null) : null;
        if (caller == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a membros desta squad ou administradores.");
        }

        // 0. Papel administrativo no registro do banco (caso o token JWT não esteja atualizado)
        if ("ADMIN".equalsIgnoreCase(caller.getRole()) || "LEAD".equalsIgnoreCase(caller.getRole())) {
            return;
        }

        // 0.1 Cargos de liderança/governança de squad
        if (isLeadershipJobTitle(caller.getJobTitle())) {
            return;
        }

        // 1. Checagem direta por squadId ou defaultProjectId
        boolean matches = (caller.getSquadId() != null && caller.getSquadId().equalsIgnoreCase(squadId))
                || (caller.getDefaultProjectId() != null && caller.getDefaultProjectId().equalsIgnoreCase(squadId));

        // 2. Tratamento de alias DDWMISSI <-> MISSI
        if (!matches && ("DDWMISSI".equalsIgnoreCase(squadId) || "MISSI".equalsIgnoreCase(squadId))) {
            matches = ("DDWMISSI".equalsIgnoreCase(caller.getSquadId()) || "MISSI".equalsIgnoreCase(caller.getSquadId()))
                    || ("DDWMISSI".equalsIgnoreCase(caller.getDefaultProjectId()) || "MISSI".equalsIgnoreCase(caller.getDefaultProjectId()));
        }

        // 3. Checagem através dos projetos resolvidos pelo UserProjectResolverService
        if (!matches && userProjectResolverService != null) {
            com.agilespace.backend.dto.UserProjectAccessDto access = userProjectResolverService.resolveUserAccess(caller);
            if (access != null) {
                if (access.isTransversalLeader()) {
                    return;
                }
                if (access.getProjects() != null) {
                    matches = access.getProjects().stream().anyMatch(p ->
                            p.getProjectId().equalsIgnoreCase(squadId)
                            || (("DDWMISSI".equalsIgnoreCase(squadId) || "MISSI".equalsIgnoreCase(squadId))
                                && ("DDWMISSI".equalsIgnoreCase(p.getProjectId()) || "MISSI".equalsIgnoreCase(p.getProjectId())))
                    );
                }
            }
        }

        // 4. Checagem se o usuário é membro registrado desta squad na tabela squad_members
        if (!matches) {
            List<SquadMember> members = squadService.getMembers(squadId);
            if ("DDWMISSI".equalsIgnoreCase(squadId) || "MISSI".equalsIgnoreCase(squadId)) {
                String aliasSquad = "DDWMISSI".equalsIgnoreCase(squadId) ? "MISSI" : "DDWMISSI";
                List<SquadMember> aliasMembers = squadService.getMembers(aliasSquad);
                if (aliasMembers != null && !aliasMembers.isEmpty()) {
                    List<SquadMember> combined = new java.util.ArrayList<>(members != null ? members : List.of());
                    combined.addAll(aliasMembers);
                    members = combined;
                }
            }
            if (members != null && !members.isEmpty()) {
                matches = members.stream().anyMatch(m ->
                        (m.getClaimedByUid() != null && m.getClaimedByUid().equals(caller.getId()))
                        || (caller.getEmail() != null && m.getEmail() != null && caller.getEmail().trim().equalsIgnoreCase(m.getEmail().trim()))
                        || (caller.getJiraAccountId() != null && m.getJiraAccountId() != null && caller.getJiraAccountId().trim().equalsIgnoreCase(m.getJiraAccountId().trim()))
                        || (caller.getName() != null && m.getDisplayName() != null && caller.getName().trim().equalsIgnoreCase(m.getDisplayName().trim()))
                );
            }
        }

        // 5. Se o usuário ainda não tem squad vinculada (ou possui marcador como "Sem Time"), auto-vincula ao squad
        boolean hasNoSquad = caller.getSquadId() == null || caller.getSquadId().isBlank() || "Sem Time".equalsIgnoreCase(caller.getSquadId().trim());
        boolean hasNoProject = caller.getDefaultProjectId() == null || caller.getDefaultProjectId().isBlank() || "Sem Time".equalsIgnoreCase(caller.getDefaultProjectId().trim());
        if (!matches && hasNoSquad) {
            caller.setSquadId(squadId);
            if (hasNoProject) {
                caller.setDefaultProjectId(squadId);
            }
            userRepository.save(caller);
            matches = true;
        }

        if (!matches) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a membros desta squad ou administradores.");
        }
    }

    // ----- Squad Config -----
    @GetMapping
    public ResponseEntity<List<Squad>> getAllSquads() {
        return ResponseEntity.ok(squadService.getAllSquads());
    }

    @GetMapping("/{squadId}")
    public ResponseEntity<Squad> getSquad(@PathVariable String squadId) {
        return squadService.getSquad(squadId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{squadId}")
    public ResponseEntity<Squad> saveSquad(@PathVariable String squadId, @RequestBody Squad squad, HttpServletRequest request) {
        requireSquadWriteAccess(squadId, request);
        squad.setId(squadId);
        return ResponseEntity.ok(squadService.saveSquad(squad));
    }

    // ----- Metrics Rollup -----
    @GetMapping("/{squadId}/rollup")
    public ResponseEntity<SquadMetricsRollup> getRollup(@PathVariable String squadId) {
        return squadService.getRollup(squadId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{squadId}/rollup")
    public ResponseEntity<SquadMetricsRollup> saveRollup(@PathVariable String squadId, @RequestBody SquadMetricsRollup rollup, HttpServletRequest request) {
        requireSquadWriteAccess(squadId, request);
        rollup.setSquadId(squadId);
        return ResponseEntity.ok(squadService.saveRollup(rollup));
    }

    // ----- Issue Snapshots -----
    @GetMapping("/{squadId}/issues")
    public ResponseEntity<List<SquadIssueSnapshot>> getIssues(
            @PathVariable String squadId,
            @RequestParam(required = false) String sprintId) {
        return ResponseEntity.ok(squadService.getIssues(squadId, sprintId));
    }

    @GetMapping("/{squadId}/issues/by-assignee")
    public ResponseEntity<List<SquadIssueSnapshot>> getIssuesByAssignee(
            @PathVariable String squadId,
            @RequestParam String assigneeId) {
        return ResponseEntity.ok(squadService.getIssuesByAssignee(squadId, assigneeId));
    }

    @GetMapping("/{squadId}/issues/{jiraKey}")
    public ResponseEntity<SquadIssueSnapshot> getIssueByKey(
            @PathVariable String squadId,
            @PathVariable String jiraKey) {
        return squadService.getIssueByKey(squadId, jiraKey)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{squadId}/issues/batch")
    public ResponseEntity<List<SquadIssueSnapshot>> batchUpsertIssues(
            @PathVariable String squadId,
            @RequestBody List<SquadIssueSnapshot> snapshots,
            HttpServletRequest request) {
        requireSquadWriteAccess(squadId, request);
        return ResponseEntity.ok(squadService.batchUpsertIssues(squadId, snapshots));
    }

    @DeleteMapping("/{squadId}/issues/batch")
    public ResponseEntity<Void> batchDeleteIssues(
            @PathVariable String squadId,
            @RequestBody List<String> keys,
            HttpServletRequest request) {
        requireSquadWriteAccess(squadId, request);
        squadService.batchDeleteIssues(squadId, keys);
        return ResponseEntity.noContent().build();
    }

    // ----- Members -----
    @GetMapping("/by-user")
    public ResponseEntity<List<SquadMember>> getSquadMembersForUser(@RequestParam String identifier) {
        return ResponseEntity.ok(squadService.getSquadMembersForUser(identifier));
    }

    @GetMapping("/{squadId}/members")
    public ResponseEntity<List<SquadMember>> getMembers(@PathVariable String squadId) {
        return ResponseEntity.ok(squadService.getMembers(squadId));
    }

    @PostMapping("/{squadId}/members/{jiraAccountId}")
    public ResponseEntity<SquadMember> saveMember(
            @PathVariable String squadId,
            @PathVariable String jiraAccountId,
            @RequestBody SquadMember member,
            HttpServletRequest request) {
        requireSquadWriteAccess(squadId, request);
        return ResponseEntity.ok(squadService.saveMember(squadId, jiraAccountId, member));
    }

    @DeleteMapping("/{squadId}/members/{jiraAccountId}")
    public ResponseEntity<Void> deleteMember(
            @PathVariable String squadId,
            @PathVariable String jiraAccountId,
            HttpServletRequest request) {
        requireSquadWriteAccess(squadId, request);
        squadService.deleteMember(squadId, jiraAccountId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{squadId}/members/batch")
    public ResponseEntity<List<SquadMember>> batchUpsertMembers(
            @PathVariable String squadId,
            @RequestBody List<SquadMember> members,
            HttpServletRequest request) {
        requireSquadWriteAccess(squadId, request);
        return ResponseEntity.ok(squadService.batchUpsertMembers(squadId, members));
    }

    // ----- Member Metrics -----
    @GetMapping("/{squadId}/member-metrics")
    public ResponseEntity<List<SquadMemberMetric>> getMemberMetrics(@PathVariable String squadId) {
        return ResponseEntity.ok(squadService.getMemberMetrics(squadId));
    }

    @PostMapping("/{squadId}/member-metrics/batch")
    public ResponseEntity<List<SquadMemberMetric>> batchUpsertMemberMetrics(
            @PathVariable String squadId,
            @RequestBody List<SquadMemberMetric> metrics,
            HttpServletRequest request) {
        requireSquadWriteAccess(squadId, request);
        return ResponseEntity.ok(squadService.batchUpsertMemberMetrics(squadId, metrics));
    }

    // ----- Daily Snapshots -----
    @GetMapping("/{squadId}/daily-snapshots")
    public ResponseEntity<List<SquadDailySnapshot>> getDailySnapshots(
            @PathVariable String squadId,
            @RequestParam(required = false) String since) {
        return ResponseEntity.ok(squadService.getDailySnapshots(squadId, since));
    }

    @PostMapping("/{squadId}/daily-snapshots/batch")
    public ResponseEntity<List<SquadDailySnapshot>> batchUpsertDailySnapshots(
            @PathVariable String squadId,
            @RequestBody List<SquadDailySnapshot> snapshots,
            HttpServletRequest request) {
        requireSquadWriteAccess(squadId, request);
        return ResponseEntity.ok(squadService.batchUpsertDailySnapshots(squadId, snapshots));
    }

    // ----- Worklog Cache -----
    @GetMapping("/{squadId}/worklog-cache")
    public ResponseEntity<List<SquadIssueWorklogCache>> getWorklogCache(
            @PathVariable String squadId,
            @RequestParam(required = false) String sprintId) {
        return ResponseEntity.ok(squadService.getWorklogCache(squadId, sprintId));
    }

    @PostMapping("/{squadId}/worklog-cache/batch")
    public ResponseEntity<List<SquadIssueWorklogCache>> batchUpsertWorklogCache(
            @PathVariable String squadId,
            @RequestBody List<SquadIssueWorklogCache> entries,
            HttpServletRequest request) {
        requireSquadWriteAccess(squadId, request);
        return ResponseEntity.ok(squadService.batchUpsertWorklogCache(squadId, entries));
    }

    @DeleteMapping("/{squadId}/worklog-cache/{jiraKey}")
    public ResponseEntity<Void> deleteWorklogCacheEntry(
            @PathVariable String squadId,
            @PathVariable String jiraKey,
            HttpServletRequest request) {
        requireSquadWriteAccess(squadId, request);
        squadService.deleteWorklogCacheEntry(squadId, jiraKey);
        return ResponseEntity.noContent().build();
    }

    // ----- Panels -----
    @GetMapping("/{squadId}/panels")
    public ResponseEntity<List<SquadPanel>> getPanels(@PathVariable String squadId, HttpServletRequest request) {
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        return ResponseEntity.ok(squadService.listPanelsForUser(squadId, userId));
    }

    @PostMapping("/{squadId}/panels")
    public ResponseEntity<SquadPanel> createPanel(
            @PathVariable String squadId,
            @Valid @RequestBody SquadPanel panel,
            HttpServletRequest request) {
        requireSquadWriteAccess(squadId, request);
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        return ResponseEntity.status(HttpStatus.CREATED).body(squadService.createPanel(squadId, userId, panel));
    }

    @PutMapping("/{squadId}/panels/{panelId}")
    public ResponseEntity<SquadPanel> updatePanel(
            @PathVariable String squadId,
            @PathVariable UUID panelId,
            @RequestBody SquadPanel panel,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        try {
            return ResponseEntity.ok(squadService.updatePanel(panelId, userId, panel));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{squadId}/panels/{panelId}")
    public ResponseEntity<Void> deletePanel(
            @PathVariable String squadId,
            @PathVariable UUID panelId,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        try {
            squadService.deletePanel(panelId, userId);
            return ResponseEntity.noContent().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
