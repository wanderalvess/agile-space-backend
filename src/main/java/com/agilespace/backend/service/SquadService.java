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

    /** Fusão de linhas da mesma pessoa e troca de chave das configurações por pessoa (opcional: ausente em testes unitários). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RosterMergeService rosterMerge;

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
        // O upsert troca a linha inteira: sem isto, o sync da sprint zerava a marca do backlog do Sprint Planner.
        java.util.Map<String, SquadIssueSnapshot> plannerRows = new java.util.HashMap<>();
        for (SquadIssueSnapshot p : nullSafe(issueSnapshotRepository.findBySquadIdAndInPlannerBacklogTrue(squadId))) {
            plannerRows.put(p.getJiraKey(), p);
        }
        for (SquadIssueSnapshot snap : snapshots) {
            SquadIssueSnapshot prior = plannerRows.get(snap.getJiraKey());
            if (prior != null && !snap.isInPlannerBacklog()) {
                snap.setInPlannerBacklog(true);
                if (snap.getPlannerSyncedAt() == null) snap.setPlannerSyncedAt(prior.getPlannerSyncedAt());
            }
        }
        return issueSnapshotRepository.saveAll(snapshots);
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list != null ? list : List.of();
    }

    @Transactional
    public void batchDeleteIssues(String squadId, List<String> keys) {
        issueSnapshotRepository.deleteBySquadIdAndJiraKeyIn(squadId, keys);
    }

    /**
     * Remoção da reconciliação do sync da sprint. Linhas que estão no backlog do Sprint Planner
     * (in_planner_backlog) não são apagadas: saem da sprint (sprint_id = PLANNER_BACKLOG) e continuam no backlog,
     * fora das listagens do Quadro/Cronograma. As demais são apagadas como antes.
     */
    @Transactional
    public void batchDeleteIssuesKeepingPlannerBacklog(String squadId, List<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        List<SquadIssueSnapshot> protectedRows = nullSafe(issueSnapshotRepository.findBySquadIdAndJiraKeyIn(squadId, keys)).stream()
                .filter(SquadIssueSnapshot::isInPlannerBacklog).toList();
        java.util.Set<String> protectedKeys = new java.util.HashSet<>();
        for (SquadIssueSnapshot row : protectedRows) {
            protectedKeys.add(row.getJiraKey());
            row.setSprintId(SquadIssueSnapshot.PLANNER_BACKLOG_SPRINT_ID);
            row.setSprintName("");
        }
        if (!protectedRows.isEmpty()) issueSnapshotRepository.saveAll(protectedRows);
        List<String> toDelete = keys.stream().filter(k -> !protectedKeys.contains(k)).toList();
        if (!toDelete.isEmpty()) issueSnapshotRepository.deleteBySquadIdAndJiraKeyIn(squadId, toDelete);
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
        member.setEmail(PersonIdentity.normEmail(member.getEmail()));
        member.setDisplayName(PersonIdentity.cleanName(member.getDisplayName()));
        jiraAccountId = PersonIdentity.cleanId(jiraAccountId);

        // Mesma pessoa = mesma conta; senão mesmo e-mail (sem caixa); senão o nome (ver PersonIdentity). Evita a segunda
        // linha com o mesmo nome e e-mail quando a pessoa chega por outra chave (uid, e-mail, "manual-...").
        Optional<SquadMember> existing = memberRepository.findBySquadIdAndJiraAccountId(squadId, jiraAccountId);
        String rekeyedFrom = null;
        if (existing.isEmpty()) {
            SquadMember same = PersonIdentity.resolve(memberRepository.findBySquadIdOrderByDisplayNameAsc(squadId),
                    jiraAccountId, member.getEmail(), member.getDisplayName());
            if (same != null) {
                if (PersonIdentity.shouldAdoptIncomingId(same.getJiraAccountId(), jiraAccountId)) {
                    // A linha nasceu com id gerado (ou com a chave interna JIRAUSER) e agora chega a conta real do Jira /
                    // o nome de usuário: troca a chave, preservando os dados (e as configurações por pessoa, abaixo).
                    rekeyedFrom = same.getJiraAccountId();
                    memberRepository.delete(same);
                    memberRepository.flush();
                } else {
                    jiraAccountId = same.getJiraAccountId();
                }
                existing = Optional.of(same);
            }
        }
        member.setSquadId(squadId);
        member.setJiraAccountId(jiraAccountId);
        member.setDbId(squadId + "_" + jiraAccountId);

        // Merge: se já existe, preserva campos que não vieram no payload
        if (existing.isPresent()) {
            SquadMember e = existing.get();
            if (member.getDisplayName() == null) member.setDisplayName(e.getDisplayName());
            if (member.getEmail() == null) member.setEmail(PersonIdentity.normEmail(e.getEmail()));
            if (member.getRole() == null) member.setRole(e.getRole());
            if (member.getCapacityHoursPerDay() == null) member.setCapacityHoursPerDay(e.getCapacityHoursPerDay());
            if (member.getSystemCalculatedCapacityHoursPerDay() == null) member.setSystemCalculatedCapacityHoursPerDay(e.getSystemCalculatedCapacityHoursPerDay());
            if (member.getCalibrationNotes() == null) member.setCalibrationNotes(e.getCalibrationNotes());
            if (member.getOverrideType() == null) member.setOverrideType(e.getOverrideType());
            if (member.getClaimedByUid() == null) member.setClaimedByUid(e.getClaimedByUid());
        }
        SquadMember saved = saveRosterRow(member);
        if (rekeyedFrom != null && rosterMerge != null) rosterMerge.movePersonConfigs(squadId, rekeyedFrom, jiraAccountId);
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
                user.setUpdatedAt(java.time.LocalDateTime.now());
                userRepository.save(user);
            }
        } catch (Exception ex) {
            log.debug("Aviso ao sincronizar usuário no saveMember: {}", ex.getMessage());
        }

        return saved;
    }

    /**
     * Grava a linha do roster e força o flush: a violação da unicidade (squad + conta, ou squad + e-mail, V85) estoura aqui
     * e vira 409 com mensagem clara, em vez de um 500 no commit.
     */
    private SquadMember saveRosterRow(SquadMember member) {
        try {
            SquadMember saved = memberRepository.save(member);
            memberRepository.flush();
            return saved;
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw rosterConflict(member, ex);
        }
    }

    private static org.springframework.web.server.ResponseStatusException rosterConflict(SquadMember member, Exception cause) {
        String who = member.getDisplayName() != null ? member.getDisplayName() : member.getJiraAccountId();
        return new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                (who == null ? "Essa pessoa" : who) + " já está no time desta squad (mesma conta ou mesmo e-mail).", cause);
    }

    @Transactional
    public List<SquadMember> batchUpsertMembers(String squadId, List<SquadMember> members) {
        // Uma leitura só do roster (sem N+1). Campo ausente no payload preserva o que já está salvo,
        // e o vínculo "sou eu" (claimedByUid) nunca é trocado por este caminho.
        // A pessoa é casada por conta > e-mail > nome (PersonIdentity), inclusive contra linhas criadas neste mesmo lote:
        // duas linhas do arquivo para a mesma pessoa viram uma só.
        List<SquadMember> pool = new ArrayList<>(memberRepository.findBySquadIdOrderByDisplayNameAsc(squadId));
        List<SquadMember> toSave = new ArrayList<>();
        List<SquadMember> toRekey = new ArrayList<>();
        Map<SquadMember, String> rekeyTo = new java.util.IdentityHashMap<>();
        for (SquadMember m : members) {
            String incomingId = PersonIdentity.cleanId(m.getJiraAccountId());
            String email = PersonIdentity.normEmail(m.getEmail());
            String name = PersonIdentity.cleanName(m.getDisplayName());
            if ((incomingId == null || incomingId.isEmpty()) && email != null) incomingId = email;
            if (incomingId == null || incomingId.isEmpty()) continue;
            m.setSquadId(squadId);
            m.setEmail(email);
            m.setDisplayName(name);
            SquadMember e = PersonIdentity.resolve(pool, incomingId, email, name);
            if (e != null) {
                boolean rekey = PersonIdentity.shouldAdoptIncomingId(e.getJiraAccountId(), incomingId);
                m.setJiraAccountId(rekey ? incomingId : e.getJiraAccountId());
                m.setDbId(rekey ? squadId + "_" + incomingId : e.getDbId());
                if (rekey) { toRekey.add(e); rekeyTo.put(e, incomingId); }
                if (m.getDisplayName() == null || m.getDisplayName().isBlank()) m.setDisplayName(e.getDisplayName());
                if (m.getEmail() == null) m.setEmail(PersonIdentity.normEmail(e.getEmail()));
                if (m.getRole() == null) m.setRole(e.getRole());
                if (m.getCapacityHoursPerDay() == null) m.setCapacityHoursPerDay(e.getCapacityHoursPerDay());
                if (m.getSystemCalculatedCapacityHoursPerDay() == null) m.setSystemCalculatedCapacityHoursPerDay(e.getSystemCalculatedCapacityHoursPerDay());
                if (m.getCalibrationNotes() == null) m.setCalibrationNotes(e.getCalibrationNotes());
                if (m.getOverrideType() == null) m.setOverrideType(e.getOverrideType());
                m.setClaimedByUid(e.getClaimedByUid());
                pool.remove(e);
            } else {
                m.setJiraAccountId(incomingId);
                m.setDbId(ownedDbId(squadId, m.getDbId(), incomingId));
                m.setClaimedByUid(null);
            }
            pool.add(m);
            toSave.removeIf(x -> x.getDbId().equals(m.getDbId()));
            toSave.add(m);
        }
        if (!toRekey.isEmpty()) {
            // Troca de chave (id gerado -> conta real do Jira): apaga a linha antiga antes, senão o INSERT bate no índice do e-mail.
            memberRepository.deleteAll(toRekey);
            memberRepository.flush();
        }
        List<SquadMember> savedAll;
        try {
            savedAll = memberRepository.saveAll(toSave);
            memberRepository.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw rosterConflict(toSave.isEmpty() ? new SquadMember() : toSave.get(0), ex);
        }
        if (rosterMerge != null) {
            for (Map.Entry<SquadMember, String> mv : rekeyTo.entrySet()) {
                rosterMerge.movePersonConfigs(squadId, mv.getKey().getJiraAccountId(), mv.getValue());
            }
        }
        for (SquadMember m : toSave) clearExclusion(squadId, m.getJiraAccountId());
        return savedAll;
    }

    /** Funde pares da mesma pessoa no roster da squad (ver {@link RosterMergeService}). Devolve quantas linhas saíram. */
    @Transactional
    public int consolidateRoster(String squadId) {
        return rosterMerge == null ? 0 : rosterMerge.consolidate(squadId);
    }

    private void clearExclusion(String squadId, String jiraAccountId) {
        if (exclusionRepository == null || jiraAccountId == null) return;
        exclusionRepository.deleteById(squadId + "_" + jiraAccountId);
    }

    @Transactional
    public void deleteMember(String squadId, String jiraAccountId) {
        memberRepository.findBySquadIdAndJiraAccountId(squadId, jiraAccountId)
                // Etapa 5 do vínculo: remover do roster não mexe mais na conta (users.squad_id foi aposentada; o
                // vínculo de acesso vive em project_member_roles e é desfeito por SquadTeamService.remove).
                .ifPresent(memberRepository::delete);
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
