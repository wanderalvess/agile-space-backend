package com.agilespace.backend.service;

import com.agilespace.backend.domain.*;
import com.agilespace.backend.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SquadService {

    private final SquadRepository squadRepository;
    private final SquadMetricsRollupRepository rollupRepository;
    private final SquadIssueSnapshotRepository issueSnapshotRepository;
    private final SquadMemberRepository memberRepository;
    private final SquadMemberMetricRepository memberMetricRepository;
    private final SquadDailySnapshotRepository dailySnapshotRepository;
    private final SquadIssueWorklogCacheRepository worklogCacheRepository;
    private final UserRepository userRepository;
    private final SquadPanelRepository panelRepository;
    private final SquadMemberExclusionRepository exclusionRepository;

    // ----- Squad Config -----
    @Transactional(readOnly = true)
    public List<Squad> getAllSquads() {
        return squadRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<Squad> getSquad(String squadId) {
        return squadRepository.findById(squadId);
    }

    @Transactional
    public Squad saveSquad(Squad squad) {
        if (squad.getId() == null || squad.getId().isBlank()) {
            throw new IllegalArgumentException("Squad ID cannot be null");
        }
        Squad target = squadRepository.findById(squad.getId()).orElse(new Squad());
        target.setId(squad.getId());

        if (squad.getName() != null && !squad.getName().isBlank()) {
            target.setName(squad.getName());
        } else if (target.getName() == null || target.getName().isBlank()) {
            target.setName(squad.getId());
        }

        if (squad.getJiraProjectKey() != null) target.setJiraProjectKey(squad.getJiraProjectKey());
        if (squad.getSyncJql() != null) target.setSyncJql(squad.getSyncJql());
        if (squad.getJiraDomain() != null) target.setJiraDomain(squad.getJiraDomain());
        if (squad.getSprintFieldId() != null) target.setSprintFieldId(squad.getSprintFieldId());
        if (squad.getRapidViewId() != null) target.setRapidViewId(squad.getRapidViewId());
        if (squad.getActiveSprintId() != null) target.setActiveSprintId(squad.getActiveSprintId());
        if (squad.getSchemaVersion() != null) target.setSchemaVersion(squad.getSchemaVersion());
        if (squad.getRankingEnabled() != null) target.setRankingEnabled(squad.getRankingEnabled());
        if (squad.getRankingEnabledAt() != null) target.setRankingEnabledAt(squad.getRankingEnabledAt());
        if (squad.getLastSyncAt() != null) target.setLastSyncAt(squad.getLastSyncAt());
        if (squad.getLastFullReconcileAt() != null) target.setLastFullReconcileAt(squad.getLastFullReconcileAt());
        if (squad.getLastSyncStatus() != null) target.setLastSyncStatus(squad.getLastSyncStatus());
        if (squad.getLastSyncBy() != null) target.setLastSyncBy(squad.getLastSyncBy());
        if (squad.getLastSyncIssueCount() != null) target.setLastSyncIssueCount(squad.getLastSyncIssueCount());
        if (squad.getLastSyncError() != null) target.setLastSyncError(squad.getLastSyncError());
        // "" = desligar o sync agendado (null continua significando "não mexer").
        if (squad.getSyncOwnerUserId() != null) {
            target.setSyncOwnerUserId(squad.getSyncOwnerUserId().isBlank() ? null : squad.getSyncOwnerUserId().trim());
        }
        if (squad.getReconcileIntervalHours() != null) target.setReconcileIntervalHours(squad.getReconcileIntervalHours());
        if (squad.getDefaultDailyCapacityHours() != null) target.setDefaultDailyCapacityHours(squad.getDefaultDailyCapacityHours());
        if (squad.getCapacityCalculationMethod() != null) target.setCapacityCalculationMethod(squad.getCapacityCalculationMethod());
        if (squad.getCapacityJql() != null) target.setCapacityJql(squad.getCapacityJql());
        if (squad.getCapacityFormula() != null) target.setCapacityFormula(squad.getCapacityFormula());
        if (squad.getUpdatedAt() != null) target.setUpdatedAt(squad.getUpdatedAt());
        if (squad.getSprintHistory() != null) target.setSprintHistory(squad.getSprintHistory());
        if (squad.getPhases() != null) target.setPhases(squad.getPhases());
        if (squad.getCeremonyMode() != null) target.setCeremonyMode(squad.getCeremonyMode());
        if (squad.getCeremonies() != null) target.setCeremonies(squad.getCeremonies());
        if (squad.getEstimationUnit() != null) target.setEstimationUnit(squad.getEstimationUnit());

        return squadRepository.save(target);
    }

    // ----- Metrics Rollup -----
    // "Atual" = rollup da sprint que o squad marcou como ativa. Antes a PK era
    // só squadId (1 rollup por squad, não por sprint) — sobrevivia por sorte
    // enquanto só a última sprint sincronizada importava; agora resolve
    // explicitamente pela sprint ativa, já que squad_metrics_rollup guarda uma
    // linha por sprint.
    @Transactional(readOnly = true)
    public Optional<SquadMetricsRollup> getRollup(String squadId) {
        String activeSprintId = squadRepository.findById(squadId)
                .map(Squad::getActiveSprintId)
                .filter(s -> s != null && !s.isBlank())
                .orElse(null);
        if (activeSprintId != null) {
            Optional<SquadMetricsRollup> active = rollupRepository.findBySquadIdAndSprintId(squadId, activeSprintId);
            if (active.isPresent()) return active;
        }
        // Sem sprint ativa gravada, ou ela não tem rollup (ex.: active_sprint_id = UNMAPPED enquanto o rollup foi
        // gravado na sprint real): devolve o mais recente em vez de 404, para o painel mostrar o que já foi sincronizado.
        return rollupRepository.findBySquadId(squadId).stream()
                .max(java.util.Comparator.comparing(r -> r.getComputedAt() == null ? "" : r.getComputedAt()));
    }

    @Transactional(readOnly = true)
    public Optional<SquadMetricsRollup> getRollup(String squadId, String sprintId) {
        return rollupRepository.findBySquadIdAndSprintId(squadId, sprintId);
    }

    @Transactional
    public SquadMetricsRollup saveRollup(SquadMetricsRollup rollup) {
        rollup.setDbId(ownedDbId(rollup.getSquadId(), rollup.getDbId(), String.valueOf(rollup.getSprintId())));
        return rollupRepository.save(rollup);
    }

    /**
     * O id da linha tem sempre o prefixo "{squadId}_". Um id vindo do cliente que aponte para outra squad
     * é descartado: com ele o saveAll sobrescreveria (e mudaria o dono de) a linha de outra squad.
     */
    static String ownedDbId(String squadId, String providedDbId, String suffix) {
        String prefix = squadId + "_";
        if (providedDbId != null && !providedDbId.isBlank() && providedDbId.startsWith(prefix) && !providedDbId.endsWith("_null")) {
            return providedDbId;
        }
        return prefix + suffix;
    }

    // ----- Issue Snapshots -----
    @Transactional(readOnly = true)
    public List<SquadIssueSnapshot> getIssues(String squadId, String sprintId) {
        if (sprintId != null && !sprintId.isBlank()) {
            return issueSnapshotRepository.findBySquadIdAndSprintId(squadId, sprintId);
        }
        return issueSnapshotRepository.findBySquadId(squadId);
    }

    @Transactional(readOnly = true)
    public List<SquadIssueSnapshot> getIssuesByAssignee(String squadId, String assigneeId) {
        return issueSnapshotRepository.findBySquadIdAndAssigneeId(squadId, assigneeId);
    }

    @Transactional(readOnly = true)
    public Optional<SquadIssueSnapshot> getIssueByKey(String squadId, String jiraKey) {
        return issueSnapshotRepository.findBySquadIdAndJiraKey(squadId, jiraKey);
    }

    @Transactional
    public List<SquadIssueSnapshot> batchUpsertIssues(String squadId, List<SquadIssueSnapshot> snapshots) {
        for (SquadIssueSnapshot snap : snapshots) {
            snap.setSquadId(squadId);
            if (snap.getJiraKey() == null || snap.getJiraKey().isBlank()) {
                if (snap.getKey() != null && !snap.getKey().isBlank()) {
                    snap.setJiraKey(snap.getKey());
                }
            }
            snap.setDbId(ownedDbId(squadId, snap.getDbId(), snap.getJiraKey() != null ? snap.getJiraKey() : "unknown"));
        }
        return issueSnapshotRepository.saveAll(snapshots);
    }

    @Transactional
    public void batchDeleteIssues(String squadId, List<String> keys) {
        issueSnapshotRepository.deleteBySquadIdAndJiraKeyIn(squadId, keys);
    }

    // ----- Members -----
    @Transactional(readOnly = true)
    public List<SquadMember> getMembers(String squadId) {
        return memberRepository.findBySquadIdOrderByDisplayNameAsc(squadId);
    }

    /** Contas do Jira que a liderança removeu à mão do time: o sync não as recoloca no roster. */
    @Transactional(readOnly = true)
    public java.util.Set<String> excludedAccountIds(String squadId) {
        if (exclusionRepository == null) return java.util.Set.of();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (SquadMemberExclusion e : exclusionRepository.findBySquadId(squadId)) ids.add(e.getJiraAccountId());
        return ids;
    }

    @Transactional(readOnly = true)
    public List<SquadMember> getSquadMembersForUser(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return java.util.Collections.emptyList();
        }
        return memberRepository.findByUserIdentifier(identifier.trim());
    }

    @Transactional
    public SquadMember saveMember(String squadId, String jiraAccountId, SquadMember member) {
        member.setSquadId(squadId);
        member.setJiraAccountId(jiraAccountId);
        member.setDbId(squadId + "_" + jiraAccountId);

        // Merge: se já existe, preserva campos que não vieram no payload
        Optional<SquadMember> existing = memberRepository.findBySquadIdAndJiraAccountId(squadId, jiraAccountId);
        if (existing.isPresent()) {
            SquadMember e = existing.get();
            if (member.getDisplayName() == null) member.setDisplayName(e.getDisplayName());
            if (member.getEmail() == null) member.setEmail(e.getEmail());
            if (member.getRole() == null) member.setRole(e.getRole());
            if (member.getCapacityHoursPerDay() == null) member.setCapacityHoursPerDay(e.getCapacityHoursPerDay());
            if (member.getSystemCalculatedCapacityHoursPerDay() == null) member.setSystemCalculatedCapacityHoursPerDay(e.getSystemCalculatedCapacityHoursPerDay());
            if (member.getCalibrationNotes() == null) member.setCalibrationNotes(e.getCalibrationNotes());
            if (member.getOverrideType() == null) member.setOverrideType(e.getOverrideType());
            if (member.getClaimedByUid() == null) member.setClaimedByUid(e.getClaimedByUid());
        }
        SquadMember saved = memberRepository.save(member);
        clearExclusion(squadId, jiraAccountId); // adicionar/editar à mão (ou aceitar convite) desfaz a exclusão

        // Sincroniza tabela central de usuários. NUNCA grava em user.role a partir daqui:
        // SquadMember.role é texto livre vindo do corpo da requisição (cargo no board),
        // enquanto User.role é o campo de autorização usado pelo JwtAuthenticationFilter
        // para liberar /api/admin/**. Deixar esse endpoint (sem checagem de admin) escrever
        // em User.role permitia que qualquer usuário autenticado se autopromovesse a ADMIN.
        try {
            User user = userRepository.findById(jiraAccountId).orElse(null);
            if (user == null && saved.getEmail() != null && !saved.getEmail().isBlank()) {
                user = userRepository.findByEmail(saved.getEmail()).orElse(null);
            }
            if (user == null) {
                user = userRepository.findByJiraAccountId(jiraAccountId).orElse(null);
            }
            if (user != null) {
                if (saved.getDisplayName() != null && !saved.getDisplayName().isBlank()) {
                    user.setName(saved.getDisplayName());
                }
                user.setSquadId(squadId);
                user.setUpdatedAt(java.time.LocalDateTime.now());
                userRepository.save(user);
            }
        } catch (Exception ex) {
            log.debug("Aviso ao sincronizar usuário no saveMember: {}", ex.getMessage());
        }

        return saved;
    }

    @Transactional
    public List<SquadMember> batchUpsertMembers(String squadId, List<SquadMember> members) {
        // Uma leitura só do roster (sem N+1). Campo ausente no payload preserva o que já está salvo,
        // e o vínculo "sou eu" (claimedByUid) nunca é trocado por este caminho.
        Map<String, SquadMember> existingByAccount = new LinkedHashMap<>();
        for (SquadMember e : memberRepository.findBySquadIdOrderByDisplayNameAsc(squadId)) {
            existingByAccount.put(e.getJiraAccountId(), e);
        }
        List<SquadMember> toSave = new ArrayList<>();
        for (SquadMember m : members) {
            if (m.getJiraAccountId() == null || m.getJiraAccountId().isBlank()) continue;
            m.setSquadId(squadId);
            SquadMember e = existingByAccount.get(m.getJiraAccountId());
            if (e != null) {
                m.setDbId(e.getDbId());
                if (m.getDisplayName() == null || m.getDisplayName().isBlank()) m.setDisplayName(e.getDisplayName());
                if (m.getEmail() == null) m.setEmail(e.getEmail());
                if (m.getRole() == null) m.setRole(e.getRole());
                if (m.getCapacityHoursPerDay() == null) m.setCapacityHoursPerDay(e.getCapacityHoursPerDay());
                if (m.getSystemCalculatedCapacityHoursPerDay() == null) m.setSystemCalculatedCapacityHoursPerDay(e.getSystemCalculatedCapacityHoursPerDay());
                if (m.getCalibrationNotes() == null) m.setCalibrationNotes(e.getCalibrationNotes());
                if (m.getOverrideType() == null) m.setOverrideType(e.getOverrideType());
                m.setClaimedByUid(e.getClaimedByUid());
            } else {
                m.setDbId(ownedDbId(squadId, m.getDbId(), m.getJiraAccountId()));
                m.setClaimedByUid(null);
            }
            toSave.add(m);
        }
        List<SquadMember> savedAll = memberRepository.saveAll(toSave);
        for (SquadMember m : toSave) clearExclusion(squadId, m.getJiraAccountId());
        return savedAll;
    }

    private void clearExclusion(String squadId, String jiraAccountId) {
        if (exclusionRepository == null || jiraAccountId == null) return;
        exclusionRepository.deleteById(squadId + "_" + jiraAccountId);
    }

    /**
     * "Sou eu": liga a conta de quem chama à linha do roster. Só vale para linha que já existe e que está
     * livre (ou já é da própria pessoa). Não mexe em nome, papel, capacidade nem no usuário.
     */
    @Transactional
    public SquadMember claimMember(String squadId, String jiraAccountId, String callerIdentifier) {
        SquadMember row = memberRepository.findBySquadIdAndJiraAccountId(squadId, jiraAccountId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Pessoa não encontrada no time desta squad."));
        String current = row.getClaimedByUid();
        if (current != null && !current.isBlank() && !current.equalsIgnoreCase(callerIdentifier)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Esta pessoa do time já está vinculada a outra conta.");
        }
        row.setClaimedByUid(callerIdentifier);
        row.setUpdatedAt(Instant.now().toString());
        return memberRepository.save(row);
    }

    @Transactional
    public void deleteMember(String squadId, String jiraAccountId) {
        memberRepository.findBySquadIdAndJiraAccountId(squadId, jiraAccountId)
                .ifPresent(m -> {
                    memberRepository.delete(m);
                    // Atualiza usuário desvinculando da squad
                    try {
                        userRepository.findByJiraAccountId(jiraAccountId).ifPresent(u -> {
                            if (squadId.equalsIgnoreCase(u.getSquadId())) {
                                u.setSquadId(null);
                                u.setUpdatedAt(java.time.LocalDateTime.now());
                                userRepository.save(u);
                            }
                        });
                    } catch (Exception ex) {
                        log.warn("Falha ao desvincular usuário {} da squad {} após remoção: {}", jiraAccountId, squadId, ex.getMessage());
                    }
                });
    }

    // ----- Member Metrics -----
    @Transactional(readOnly = true)
    public List<SquadMemberMetric> getMemberMetrics(String squadId) {
        return memberMetricRepository.findBySquadId(squadId);
    }

    @Transactional
    public List<SquadMemberMetric> batchUpsertMemberMetrics(String squadId, List<SquadMemberMetric> metrics) {
        for (SquadMemberMetric m : metrics) {
            m.setSquadId(squadId);
            m.setDbId(ownedDbId(squadId, m.getDbId(), String.valueOf(m.getAssigneeId())));
        }
        memberMetricRepository.deleteBySquadId(squadId);
        return memberMetricRepository.saveAll(metrics);
    }

    // ----- Daily Snapshots -----
    @Transactional(readOnly = true)
    public List<SquadDailySnapshot> getDailySnapshots(String squadId, String since) {
        if (since != null && !since.isBlank()) {
            return dailySnapshotRepository.findBySquadIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(squadId, since);
        }
        return dailySnapshotRepository.findBySquadIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(squadId, "0000-00-00");
    }

    @Transactional
    public List<SquadDailySnapshot> batchUpsertDailySnapshots(String squadId, List<SquadDailySnapshot> snapshots) {
        for (SquadDailySnapshot s : snapshots) {
            s.setSquadId(squadId);
            s.setDbId(ownedDbId(squadId, s.getDbId(), String.valueOf(s.getSnapshotDate())));
        }
        return dailySnapshotRepository.saveAll(snapshots);
    }

    // ----- Worklog Cache -----
    @Transactional(readOnly = true)
    public List<SquadIssueWorklogCache> getWorklogCache(String squadId, String sprintId) {
        if (sprintId != null && !sprintId.isBlank()) {
            return worklogCacheRepository.findBySquadIdAndSprintId(squadId, sprintId);
        }
        return worklogCacheRepository.findBySquadId(squadId);
    }

    @Transactional
    public List<SquadIssueWorklogCache> batchUpsertWorklogCache(String squadId, List<SquadIssueWorklogCache> entries) {
        for (SquadIssueWorklogCache w : entries) {
            w.setSquadId(squadId);
            if (w.getJiraKey() == null || w.getJiraKey().isBlank()) {
                if (w.getKey() != null && !w.getKey().isBlank()) {
                    w.setJiraKey(w.getKey());
                }
            }
            w.setDbId(ownedDbId(squadId, w.getDbId(), w.getJiraKey() != null ? w.getJiraKey() : "unknown"));
        }
        return worklogCacheRepository.saveAll(entries);
    }

    @Transactional
    public void deleteWorklogCacheEntry(String squadId, String jiraKey) {
        worklogCacheRepository.deleteById(squadId + "_" + jiraKey);
    }

    // ----- Panels -----
    @Transactional(readOnly = true)
    public List<SquadPanel> listPanelsForUser(String squadId, String requestingUserIdentifier) {
        Map<UUID, SquadPanel> merged = new LinkedHashMap<>();
        for (SquadPanel panel : panelRepository.findBySquadIdAndVisibility(squadId, "SQUAD")) {
            merged.put(panel.getId(), panel);
        }
        for (SquadPanel panel : panelRepository.findBySquadIdAndOwnerId(squadId, requestingUserIdentifier)) {
            merged.put(panel.getId(), panel);
        }
        return new ArrayList<>(merged.values());
    }

    @Transactional
    public SquadPanel createPanel(String squadId, String ownerId, SquadPanel panel) {
        panel.setSquadId(squadId);
        panel.setOwnerId(ownerId);
        panel.setUpdatedAt(LocalDateTime.now());
        return panelRepository.save(panel);
    }

    @Transactional
    public SquadPanel updatePanel(UUID panelId, String requestingUserIdentifier, SquadPanel updates) {
        SquadPanel existing = panelRepository.findById(panelId)
                .orElseThrow(() -> new IllegalArgumentException("Panel not found: " + panelId));

        if (!existing.getOwnerId().equals(requestingUserIdentifier)) {
            throw new SecurityException("Only the panel owner can modify this panel");
        }

        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getType() != null) existing.setType(updates.getType());
        if (updates.getConfig() != null) existing.setConfig(updates.getConfig());
        if (updates.getVisibility() != null) existing.setVisibility(updates.getVisibility());
        existing.setUpdatedAt(LocalDateTime.now());

        return panelRepository.save(existing);
    }

    @Transactional
    public void deletePanel(UUID panelId, String requestingUserIdentifier) {
        SquadPanel existing = panelRepository.findById(panelId)
                .orElseThrow(() -> new IllegalArgumentException("Panel not found: " + panelId));

        if (!existing.getOwnerId().equals(requestingUserIdentifier)) {
            throw new SecurityException("Only the panel owner can delete this panel");
        }

        panelRepository.delete(existing);
    }
}
