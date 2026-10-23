package com.agilespace.backend.service.sprintplan;

import com.agilespace.backend.domain.SprintPlan;
import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.domain.SquadIssueSnapshot;
import com.agilespace.backend.dto.SprintPlanDistributionDtos.*;
import com.agilespace.backend.dto.SprintPlanDtos.ItemView;
import com.agilespace.backend.dto.SprintPlanReviewDtos.*;
import com.agilespace.backend.repository.SprintPlanRepository;
import com.agilespace.backend.repository.SquadIssueSnapshotRepository;
import com.agilespace.backend.service.JiraDateFieldResolver;
import com.agilespace.backend.service.SquadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Revisão do plano (E5): resumo por pessoa/tarefa, pendências por severidade e prévia (somente leitura, a partir do
 * snapshot) do que seria gravado no Jira. NÃO escreve nada no Jira: as regras de escrita são da E6, então
 * {@code canSend} é sempre false por enquanto. Os cálculos vêm da Distribuição e do Escopo (sem regra duplicada).
 */
@Service
@RequiredArgsConstructor
public class SprintPlanReviewService {

    public static final String RULES_NOTE = "Regras de escrita no Jira em definição";
    public static final String FIELD_TBD = "campo a definir";
    /** Pendências da Distribuição que impedem o envio; as demais só avisam. */
    static final Set<String> BLOCKING_CODES = Set.of("SUBTASK_UNASSIGNED", "SUBTASK_NO_DATES", "QA_BEFORE_DEV",
            "CODE_REVIEW_BEFORE_CODING", "DATES_OUTSIDE_SPRINT");

    private final SprintPlanRepository planRepository;
    private final SprintPlanDistributionService distributionService;
    private final SprintPlanScopeService scopeService;
    private final SquadIssueSnapshotRepository snapshotRepository;
    private final SquadService squadService;
    private final JiraDateFieldResolver fieldResolver;

    @Transactional(readOnly = true)
    public ReviewView review(String squadId, String planId, boolean canEditPlan) {
        SprintPlan plan = planRepository.findById(planId).filter(p -> p.getSquadId().equals(squadId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plano não encontrado."));
        DistributionView dist = distributionService.buildView(plan, new SprintPlanDistributionService.Caller(null, false));
        SprintPlanScopeService.Computed scope = scopeService.compute(plan);
        Squad squad = squadService.getSquad(squadId).orElse(null);

        List<ReviewTask> tasks = new ArrayList<>();
        Map<String, int[]> counts = new HashMap<>();
        for (DistTask t : dist.tasks()) {
            List<ReviewSubtask> subs = new ArrayList<>();
            for (DistSubtask s : t.subtasks()) {
                if (!isDistributable(s)) continue;
                subs.add(new ReviewSubtask(s.key(), s.title(), s.typeName(), s.cls(), s.assigneeId(), s.assigneeName(),
                        s.startDate(), s.endDate(), s.countedH()));
                if (s.assigneeId() != null) {
                    int[] c = counts.computeIfAbsent(s.assigneeId(), k -> new int[2]);
                    c[0]++;
                    if (s.startDate() != null && s.endDate() != null) c[1]++;
                }
            }
            tasks.add(new ReviewTask(t.itemId(), t.parentKey(), t.title(), t.devHours(), t.qaHours(), subs));
        }
        List<ReviewPerson> people = dist.people().stream().map(p -> {
            int[] c = counts.getOrDefault(p.accountId(), new int[2]);
            return new ReviewPerson(p.accountId(), p.name(), p.role(), p.capacityHours(), p.allocatedHours(),
                    p.freeHours(), p.pct(), p.zone(), p.state(), c[0], c[1]);
        }).toList();

        List<Pending> pendings = pendings(plan, dist, scope);
        int blocking = (int) pendings.stream().filter(p -> "BLOCKING".equals(p.severity())).count();
        int warning = pendings.size() - blocking;
        boolean sent = SprintPlan.STATUS_ENVIADO.equals(plan.getStatus());
        JiraPreview preview = preview(plan, squad, dist, sent);
        return new ReviewView(plan.getId(), plan.getStatus(), dist.syncedAt(), canEditPlan,
                new ReviewSprint(plan.getSprintName(), plan.getStartDate(), plan.getEndDate()), plan.getGoal(), sent,
                people, tasks, pendings, blocking, warning, false, false, preview, jiraSprintUrl(squad, plan));
    }

    private static boolean isDistributable(DistSubtask s) {
        return "DEV".equals(s.cls()) || "QA".equals(s.cls());
    }

    // ---------------- Pendências ----------------

    static List<Pending> pendings(SprintPlan plan, DistributionView dist, SprintPlanScopeService.Computed scope) {
        List<Pending> out = new ArrayList<>();
        if (dist.tasks().isEmpty()) {
            out.add(new Pending("SCOPE_EMPTY", "BLOCKING", "O escopo da sprint está vazio.", null, null, null));
        }
        Map<String, String> itemBySub = new HashMap<>();
        dist.tasks().forEach(t -> t.subtasks().forEach(s -> itemBySub.put(s.key(), t.itemId())));
        for (DistWarning w : dist.warnings()) {
            String sev = BLOCKING_CODES.contains(w.code()) ? "BLOCKING" : "WARNING";
            out.add(new Pending(w.code(), sev, w.message(), w.subtaskKey(), w.accountId(),
                    w.subtaskKey() == null ? null : itemBySub.get(w.subtaskKey())));
        }
        for (ItemView it : scope.items()) {
            if (it.divergent()) {
                out.add(new Pending("TITLE_DIVERGENCE", "WARNING", it.parentKey()
                        + ": as horas do título diferem da soma das subtarefas.", null, null, it.id()));
            }
            if (it.noEstimate()) {
                out.add(new Pending("NO_ESTIMATE", "WARNING", it.parentKey() + " está sem estimativa.", null, null, it.id()));
            }
        }
        if (scope.totals().unclassifiedCount() > 0) {
            out.add(new Pending("UNCLASSIFIED_TYPES", "WARNING", scope.totals().unclassifiedCount()
                    + " subtarefa(s) de tipo sem classificação não entram no total.", null, null, null));
        }
        if (plan.getGoal() == null || plan.getGoal().isBlank()) {
            out.add(new Pending("GOAL_EMPTY", "WARNING", "A meta da sprint está vazia.", null, null, null));
        }
        out.sort(Comparator.comparing((Pending p) -> !"BLOCKING".equals(p.severity())));
        return out;
    }

    // ---------------- Prévia ----------------

    JiraPreview preview(SprintPlan plan, Squad squad, DistributionView dist, boolean sent) {
        String startName = fieldName(squad, true);
        String endName = fieldName(squad, false);
        Set<String> keys = new LinkedHashSet<>();
        dist.tasks().forEach(t -> {
            keys.add(t.parentKey());
            t.subtasks().forEach(s -> keys.add(s.key()));
        });
        Map<String, SquadIssueSnapshot> snap = new HashMap<>();
        if (!keys.isEmpty()) {
            List<SquadIssueSnapshot> rows = snapshotRepository.findBySquadIdAndJiraKeyIn(plan.getSquadId(), keys);
            if (rows != null) rows.forEach(s -> snap.put(s.getJiraKey(), s));
        }
        List<PreviewChange> changes = new ArrayList<>();
        for (DistTask t : dist.tasks()) {
            SquadIssueSnapshot ps = snap.get(t.parentKey());
            boolean anyAssigned = t.subtasks().stream().anyMatch(s -> s.assigneeId() != null && isDistributable(s));
            if (anyAssigned) {
                changes.add(change(t.parentKey(), t.title(), "sprint", null, ps == null ? null : ps.getSprintName(),
                        plan.getSprintName()));
            }
            for (DistSubtask s : t.subtasks()) {
                if (s.assigneeId() == null || !isDistributable(s)) continue;
                SquadIssueSnapshot ss = snap.get(s.key());
                changes.add(assigneeChange(s, ss));
                changes.add(change(s.key(), s.title(), "sprint", null, ss == null ? null : ss.getSprintName(),
                        plan.getSprintName()));
                if (s.startDate() != null) {
                    changes.add(change(s.key(), s.title(), "plannedStart", startName, realDate(ss, true),
                            s.startDate().toString()));
                }
                if (s.endDate() != null) {
                    changes.add(change(s.key(), s.title(), "plannedEnd", endName, realDate(ss, false),
                            s.endDate().toString()));
                }
            }
        }
        Set<String> issues = new LinkedHashSet<>();
        changes.forEach(c -> issues.add(c.issueKey()));
        int set = (int) changes.stream().filter(c -> "SET".equals(c.kind())).count();
        int chg = (int) changes.stream().filter(c -> "CHANGE".equals(c.kind())).count();
        int same = changes.size() - set - chg;
        String goal = plan.getGoal() == null || plan.getGoal().isBlank() ? null : plan.getGoal().trim();
        GoalChange goalChange = goalChange(plan.getJiraGoal(), goal);
        String note = sent ? RULES_NOTE + ". Mudanças depois do envio: regra em definição." : RULES_NOTE;
        return new JiraPreview(note, goalChange, changes, new PreviewCounts(set, chg, same, issues.size()));
    }

    /** SET: Jira sem meta; CHANGE: meta do Jira difere da do plano; UNCHANGED: igual (ou plano sem meta). */
    static GoalChange goalChange(String jiraGoal, String planGoal) {
        String from = jiraGoal == null || jiraGoal.isBlank() ? null : jiraGoal.trim();
        if (planGoal == null) return new GoalChange(from, null, "UNCHANGED");
        String kind = from == null ? "SET" : from.equals(planGoal) ? "UNCHANGED" : "CHANGE";
        return new GoalChange(from, planGoal, kind);
    }

    private static PreviewChange assigneeChange(DistSubtask s, SquadIssueSnapshot ss) {
        String fromId = ss == null ? null : ss.getAssigneeId();
        String fromName = fromId == null ? null : (ss.getAssigneeName() != null ? ss.getAssigneeName() : fromId);
        String kind = fromId == null ? "SET" : fromId.equals(s.assigneeId()) ? "UNCHANGED" : "CHANGE";
        return new PreviewChange(s.key(), s.title(), "assignee", null, fromName,
                s.assigneeName() != null ? s.assigneeName() : s.assigneeId(), kind);
    }

    private static PreviewChange change(String key, String summary, String field, String jiraField, String from, String to) {
        String f = from == null || from.isBlank() ? null : from.trim();
        boolean date = field.startsWith("planned");
        if (date && f != null && f.length() >= 10) f = f.substring(0, 10);
        String kind = f == null ? "SET" : f.equals(to) ? "UNCHANGED" : "CHANGE";
        return new PreviewChange(key, summary, field, jiraField, f, to, kind);
    }

    /** Valor real do campo no Jira (data inferida pelo sync não conta como valor atual). */
    private static String realDate(SquadIssueSnapshot s, boolean start) {
        if (s == null || Boolean.TRUE.equals(s.getDatesAreInferred())) return null;
        return start ? s.getTargetStart() : s.getTargetEnd();
    }

    /** Nome real do campo (resolvedor, sem chamar o Jira) ou "campo a definir". */
    private String fieldName(Squad squad, boolean start) {
        if (squad == null) return FIELD_TBD;
        String configured = start ? squad.getJiraStartField() : squad.getJiraEndField();
        JiraDateFieldResolver.Resolution r = squad.getJiraDomain() == null ? null : fieldResolver.peek(squad.getJiraDomain());
        String id = JiraDateFieldResolver.pick(configured, r == null ? List.of() : (start ? r.start() : r.end()));
        if (id == null) return FIELD_TBD;
        String name = r == null ? null : r.namesById().get(id);
        return name != null ? name : id;
    }

    static String jiraSprintUrl(Squad squad) {
        return jiraSprintUrl(squad, null);
    }

    /** Link do quadro; com board vira planning e, com a sprint do plano, já seleciona a sprint. */
    static String jiraSprintUrl(Squad squad, SprintPlan plan) {
        if (squad == null || squad.getJiraDomain() == null || squad.getJiraDomain().isBlank()) return null;
        String d = squad.getJiraDomain().trim().replaceFirst("^https?://", "").replaceAll("/+$", "");
        if (d.isEmpty()) return null;
        String base = "https://" + d + "/secure/RapidBoard.jspa";
        String board = squad.getRapidViewId() == null ? "" : squad.getRapidViewId().trim();
        if (!board.matches("[0-9]+")) return base;
        String url = base + "?rapidView=" + board + "&view=planning&selectedIssue=";
        String sprint = plan == null || plan.getJiraSprintId() == null ? "" : plan.getJiraSprintId().trim();
        if (sprint.matches("[0-9]+")) url += "&sprint=" + sprint;
        return url;
    }
}
