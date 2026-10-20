package com.agilespace.backend.service;

import com.agilespace.backend.domain.SquadIssueSnapshot;
import com.agilespace.backend.domain.SubtaskClass;
import com.agilespace.backend.repository.SquadIssueSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Backlog do Sprint Planner já classificado, só a partir do snapshot (nenhuma chamada ao Jira).
 * Para cada pai: horas do título "(DEV xh / QA yh)", subtarefas com tipo e classe, soma por classe e divergência
 * título x subtarefas. Pai fora do status de refinamento é sobra da sprint ativa (carry-over): conta o RESTANTE.
 */
@Service
@RequiredArgsConstructor
public class PlannerBacklogService {

    static final BigDecimal DIVERGENCE_TOLERANCE = new BigDecimal("0.01");
    private static final BigDecimal SECONDS_PER_HOUR = new BigDecimal(3600);

    public record HoursPair(BigDecimal dev, BigDecimal qa) {}

    public record SubtaskView(String key, String title, String typeId, String typeName, SubtaskClass subtaskClass,
                              BigDecimal estimateH, BigDecimal remainingH, BigDecimal countedH, boolean defaultHoursApplied,
                              String status, String statusCategory, String assigneeId, String assigneeName) {}

    public record ParentView(String key, String title, String status, boolean carryOver, HoursPair titleHours,
                             List<SubtaskView> subtasks, Map<SubtaskClass, BigDecimal> sumByClass,
                             boolean divergenceDev, boolean divergenceQa, boolean divergent) {}

    public record UnclassifiedType(String typeId, String typeName, int count, BigDecimal hours) {}

    public record BacklogView(String syncedAt, String sprintKey, List<ParentView> items, List<UnclassifiedType> unclassifiedTypes) {}

    private final SquadIssueSnapshotRepository snapshotRepository;
    private final SubtaskTypeMappingService mappingService;
    private final PlannerBacklogSyncService syncService;

    @Transactional(readOnly = true)
    public BacklogView view(String squadId, String sprintKey, String query, boolean onlyWithHours) {
        String key = SubtaskTypeMappingService.normalizeSprintKey(sprintKey);
        List<SquadIssueSnapshot> rows = snapshotRepository.findBySquadIdAndInPlannerBacklogTrue(squadId);
        Map<String, SquadIssueSnapshot> parents = new LinkedHashMap<>();
        Map<String, List<SquadIssueSnapshot>> subsByParent = new HashMap<>();
        String syncedAt = null;
        for (SquadIssueSnapshot r : rows) {
            if (r.getPlannerSyncedAt() != null && (syncedAt == null || r.getPlannerSyncedAt().compareTo(syncedAt) > 0)) {
                syncedAt = r.getPlannerSyncedAt();
            }
        }
        for (SquadIssueSnapshot r : rows) {
            if (r.getParentKey() == null || r.getParentKey().isBlank()) parents.put(r.getJiraKey(), r);
        }
        List<SubtaskTypeMappingService.TypeRef> refs = new ArrayList<>();
        for (SquadIssueSnapshot r : rows) {
            if (r.getParentKey() != null && parents.containsKey(r.getParentKey())) {
                subsByParent.computeIfAbsent(r.getParentKey(), k -> new ArrayList<>()).add(r);
                if (r.getIssueTypeId() != null) refs.add(new SubtaskTypeMappingService.TypeRef(r.getIssueTypeId(), r.getType()));
            }
        }
        Map<String, SubtaskTypeMappingService.ResolvedType> types = mappingService.resolveMap(squadId, key, refs);

        List<ParentView> items = new ArrayList<>();
        Map<String, UnclassifiedType> unclassified = new LinkedHashMap<>();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (SquadIssueSnapshot p : parents.values()) {
            if (!q.isEmpty() && !(p.getJiraKey().toLowerCase(Locale.ROOT).contains(q)
                    || (p.getTitle() != null && p.getTitle().toLowerCase(Locale.ROOT).contains(q)))) continue;
            List<SquadIssueSnapshot> subs = new ArrayList<>(subsByParent.getOrDefault(p.getJiraKey(), List.of()));
            subs.sort(Comparator.comparing(SquadIssueSnapshot::getJiraKey, PlannerBacklogService::compareKeys));
            ParentView view = buildParent(p, subs, types, isCarryOver(p));
            if (onlyWithHours && view.titleHours() == null) continue;
            items.add(view);
            for (SubtaskView s : view.subtasks()) {
                if (s.subtaskClass() != SubtaskClass.SEM_CLASSIFICACAO) continue;
                String typeKey = s.typeId() != null ? s.typeId() : "nome:" + s.typeName();
                UnclassifiedType prev = unclassified.get(typeKey);
                unclassified.put(typeKey, new UnclassifiedType(s.typeId(), s.typeName(),
                        (prev != null ? prev.count() : 0) + 1, (prev != null ? prev.hours() : BigDecimal.ZERO).add(s.countedH())));
            }
        }
        // Sobra primeiro (já estava na sprint), depois as refinadas; dentro de cada grupo, pela chave.
        items.sort(Comparator.comparing((ParentView v) -> !v.carryOver()).thenComparing(ParentView::key, PlannerBacklogService::compareKeys));
        return new BacklogView(syncedAt, key, items, new ArrayList<>(unclassified.values()));
    }

    boolean isCarryOver(SquadIssueSnapshot parent) {
        String statusId = syncService.refinementStatusId();
        if (!statusId.isEmpty() && parent.getStatusId() != null) return !statusId.equals(parent.getStatusId());
        return !SubtaskTypeMappingService.normalizeName(syncService.refinementStatusName())
                .equals(SubtaskTypeMappingService.normalizeName(parent.getStatus()));
    }

    static ParentView buildParent(SquadIssueSnapshot p, List<SquadIssueSnapshot> subs,
                                  Map<String, SubtaskTypeMappingService.ResolvedType> types, boolean carryOver) {
        Map<SubtaskClass, BigDecimal> sums = new EnumMap<>(SubtaskClass.class);
        for (SubtaskClass c : SubtaskClass.values()) sums.put(c, BigDecimal.ZERO);
        List<SubtaskView> views = new ArrayList<>();
        for (SquadIssueSnapshot s : subs) {
            SubtaskTypeMappingService.ResolvedType type = s.getIssueTypeId() != null ? types.get(s.getIssueTypeId()) : null;
            if (type == null) type = SubtaskTypeMappingService.seeded(s.getIssueTypeId(), s.getType(), Set.of());
            SubtaskClass cls = SubtaskTypeMappingService.classify(type, s.getTitle());
            BigDecimal est = hours(s.getOwnOriginalEstimateSec());
            BigDecimal rem = hours(s.getOwnRemainingSec());
            boolean defaultApplied = false;
            if (est.signum() == 0 && type.defaultHours() != null && type.defaultHours().signum() > 0) {
                est = type.defaultHours();
                if (s.getOwnRemainingSec() == null) rem = est;
                defaultApplied = true;
            }
            BigDecimal counted = carryOver ? rem : est;
            sums.merge(cls, counted, BigDecimal::add);
            views.add(new SubtaskView(s.getJiraKey(), s.getTitle(), s.getIssueTypeId(), s.getType(), cls, est, rem, counted,
                    defaultApplied, s.getStatus(), s.getStatusCategory(), s.getAssigneeId(), s.getAssigneeName()));
        }
        PokerHoursTitleParser.TitleHours th = PokerHoursTitleParser.parse(p.getTitle());
        HoursPair titleHours = th == null ? null : new HoursPair(th.dev(), th.qa());
        boolean divDev = isDivergent(carryOver, th == null ? null : th.dev(), sums.get(SubtaskClass.DEV));
        boolean divQa = isDivergent(carryOver, th == null ? null : th.qa(), sums.get(SubtaskClass.QA));
        return new ParentView(p.getJiraKey(), p.getTitle(), p.getStatus(), carryOver, titleHours, views, sums,
                divDev, divQa, divDev || divQa);
    }

    /** Divergência só faz sentido para item novo com horas no título; sobra conta o restante e não compara. */
    static boolean isDivergent(boolean carryOver, BigDecimal titleHours, BigDecimal subtaskSum) {
        if (carryOver || titleHours == null) return false;
        return titleHours.subtract(subtaskSum).abs().compareTo(DIVERGENCE_TOLERANCE) > 0;
    }

    static BigDecimal hours(Long seconds) {
        if (seconds == null || seconds <= 0) return BigDecimal.ZERO;
        return new BigDecimal(seconds).divide(SECONDS_PER_HOUR, 2, RoundingMode.HALF_UP);
    }

    /** PROJ-9 antes de PROJ-10. */
    static int compareKeys(String a, String b) {
        int ia = a.lastIndexOf('-');
        int ib = b.lastIndexOf('-');
        if (ia > 0 && ib > 0 && a.substring(0, ia).equals(b.substring(0, ib))) {
            try {
                return Long.compare(Long.parseLong(a.substring(ia + 1)), Long.parseLong(b.substring(ib + 1)));
            } catch (NumberFormatException ignored) {
                // cai na comparação de texto
            }
        }
        return a.compareTo(b);
    }
}
