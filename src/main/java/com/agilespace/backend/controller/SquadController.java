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

    /**
     * Só ADMIN/LEAD ou um membro já vinculado a esta squad (User.squadId) pode gravar
     * dados nela. Antes disso qualquer usuário autenticado podia criar/sobrescrever
     * dados de qualquer squad só trocando o squadId na URL.
     */
    private void requireSquadWriteAccess(String squadId, HttpServletRequest request) {
        String role = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
        if ("ADMIN".equalsIgnoreCase(role) || "LEAD".equalsIgnoreCase(role)) {
            return;
        }
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        User caller = userId != null ? userRepository.findById(userId).orElse(null) : null;
        if (caller == null || caller.getSquadId() == null || !caller.getSquadId().equalsIgnoreCase(squadId)) {
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
