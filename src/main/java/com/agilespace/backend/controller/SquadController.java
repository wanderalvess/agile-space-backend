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
    private final com.agilespace.backend.service.SquadSyncService squadSyncService;
    private final com.agilespace.backend.service.SquadSyncGuard squadSyncGuard;
    private final UserRepository userRepository;
    private final com.agilespace.backend.service.UserProjectResolverService userProjectResolverService;

    private boolean isLeadershipJobTitle(String jobTitle) {
        return com.agilespace.backend.security.SquadLeadership.isLeadershipJobTitle(jobTitle);
    }

    /**
     * Núcleo comum de leitura E escrita: ADMIN/LEAD, ou um caller já vinculado a esta squad
     * (User.squadId/defaultProjectId, papel em projeto resolvido, ou membro no roster
     * squad_members). Não tem efeito colateral nenhum — nunca grava nada — por isso serve
     * tanto pra decidir leitura (Fase 5 do plano de unificação Squad Pulse + jiradash: antes
     * SÓ escrita checava algo, GET de /api/squads/** não checava nada além de autenticação)
     * quanto como primeira parte de requireSquadWriteAccess.
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

        // 0. Papel administrativo no registro do banco (caso o token JWT não esteja atualizado)
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

        // 3. Checagem através dos projetos resolvidos pelo UserProjectResolverService
        if (userProjectResolverService != null) {
            com.agilespace.backend.dto.UserProjectAccessDto access = userProjectResolverService.resolveUserAccess(caller);
            if (access != null) {
                if (access.isTransversalLeader()) {
                    return true;
                }
                if (access.getProjects() != null) {
                    matches = access.getProjects().stream().anyMatch(p ->
                            p.getProjectId().equalsIgnoreCase(squadId)
                            || (("DDWMISSI".equalsIgnoreCase(squadId) || "MISSI".equalsIgnoreCase(squadId))
                                && ("DDWMISSI".equalsIgnoreCase(p.getProjectId()) || "MISSI".equalsIgnoreCase(p.getProjectId())))
                    );
                    if (matches) return true;
                }
            }
        }

        // 4. Checagem se o usuário é membro registrado desta squad na tabela squad_members
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
        return matches;
    }

    /**
     * Leitura: qualquer membro real da squad (ou admin/liderança) — nunca auto-vincula.
     * Antes desta checagem, todo GET de /api/squads/** exigia só autenticação, sem checar
     * pertencimento — qualquer usuário autenticado da aplicação lia dado de qualquer squad.
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

        // Se o usuário ainda não tem squad vinculada (ou possui marcador como "Sem Time"), auto-vincula ao squad
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

    // ----- Squad Config -----
    @GetMapping
    public ResponseEntity<List<Squad>> getAllSquads() {
        return ResponseEntity.ok(squadService.getAllSquads());
    }

    // Motor de sync roda no backend (SquadSyncService) em vez de client-side —
    // ver plano de unificação Squad Pulse + jiradash, Fase 2. callerUserId vem
    // sempre do token validado, nunca do corpo da requisição, senão qualquer
    // chamador autenticado poderia sincronizar usando o PAT salvo de outra
    // pessoa só citando o userId dela.
    @PostMapping("/{squadId}/sync")
    public ResponseEntity<Void> syncSquad(
            @PathVariable String squadId,
            @RequestParam(required = false, defaultValue = "false") boolean forceFull,
            HttpServletRequest request) {
        requireSquadWriteAccess(squadId, request);
        // Sem isso, um tick do sync agendado (SquadSyncScheduler) podia coincidir com
        // alguém clicando "Sincronizar" ao mesmo tempo — duas syncs do mesmo squad
        // escrevendo por cima uma da outra. Risco que só existe desde que o sync
        // agendado existe (Fase 4); antes só um humano por vez clicava o botão.
        if (!squadSyncGuard.tryAcquire(squadId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma sincronização em andamento para esta squad.");
        }
        try {
            String callerUserId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
            squadSyncService.syncSquad(squadId, callerUserId, forceFull);
            return ResponseEntity.ok().build();
        } finally {
            squadSyncGuard.release(squadId);
        }
    }

    @PostMapping("/{squadId}/force-resync-sprint")
    public ResponseEntity<Void> forceResyncSprint(
            @PathVariable String squadId,
            @RequestParam String sprintId,
            HttpServletRequest request) {
        requireSquadWriteAccess(squadId, request);
        if (!squadSyncGuard.tryAcquire(squadId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma sincronização em andamento para esta squad.");
        }
        try {
            String callerUserId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
            squadSyncService.forceResyncSprint(squadId, callerUserId, sprintId);
            return ResponseEntity.ok().build();
        } finally {
            squadSyncGuard.release(squadId);
        }
    }

    @GetMapping("/{squadId}")
    public ResponseEntity<Squad> getSquad(@PathVariable String squadId, HttpServletRequest request) {
        requireSquadReadAccess(squadId, request);
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
    public ResponseEntity<SquadMetricsRollup> getRollup(@PathVariable String squadId, HttpServletRequest request) {
        requireSquadReadAccess(squadId, request);
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
            @RequestParam(required = false) String sprintId,
            HttpServletRequest request) {
        requireSquadReadAccess(squadId, request);
        return ResponseEntity.ok(squadService.getIssues(squadId, sprintId));
    }

    @GetMapping("/{squadId}/issues/by-assignee")
    public ResponseEntity<List<SquadIssueSnapshot>> getIssuesByAssignee(
            @PathVariable String squadId,
            @RequestParam String assigneeId,
            HttpServletRequest request) {
        requireSquadReadAccess(squadId, request);
        return ResponseEntity.ok(squadService.getIssuesByAssignee(squadId, assigneeId));
    }

    @GetMapping("/{squadId}/issues/{jiraKey}")
    public ResponseEntity<SquadIssueSnapshot> getIssueByKey(
            @PathVariable String squadId,
            @PathVariable String jiraKey,
            HttpServletRequest request) {
        requireSquadReadAccess(squadId, request);
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
    public ResponseEntity<List<SquadMember>> getMembers(@PathVariable String squadId, HttpServletRequest request) {
        requireSquadReadAccess(squadId, request);
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
    public ResponseEntity<List<SquadMemberMetric>> getMemberMetrics(@PathVariable String squadId, HttpServletRequest request) {
        requireSquadReadAccess(squadId, request);
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
            @RequestParam(required = false) String since,
            HttpServletRequest request) {
        requireSquadReadAccess(squadId, request);
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
            @RequestParam(required = false) String sprintId,
            HttpServletRequest request) {
        requireSquadReadAccess(squadId, request);
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
        requireSquadReadAccess(squadId, request);
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
