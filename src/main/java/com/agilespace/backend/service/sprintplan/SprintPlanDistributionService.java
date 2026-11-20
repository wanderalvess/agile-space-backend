package com.agilespace.backend.service.sprintplan;

import com.agilespace.backend.domain.*;
import com.agilespace.backend.dto.SprintPlanDistributionDtos.*;
import com.agilespace.backend.dto.SprintPlanDtos.CapacityRow;
import com.agilespace.backend.dto.SprintPlanDtos.CapacityView;
import com.agilespace.backend.repository.*;
import com.agilespace.backend.service.JiraDateParser;
import com.agilespace.backend.service.PlannerBacklogService;
import com.agilespace.backend.service.PlannerBacklogService.ParentView;
import com.agilespace.backend.service.PlannerBacklogService.SubtaskView;
import com.agilespace.backend.service.sprintplan.SprintPlanChain.Level;
import com.agilespace.backend.service.sprintplan.SprintPlanChain.Node;
import com.agilespace.backend.websocket.SprintPlanWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Distribuição do plano (E4): cada DEV/QA assume as PRÓPRIAS subtarefas e marca início e fim; só liderança/ADMIN
 * atribui a outra pessoa. Uma subtarefa = um responsável (restrição única plan_id+subtask_key). Escritas só na etapa
 * DISTRIBUICAO e serializadas por plano (trava da linha do plano). A cadeia e a cascata de datas ficam em
 * {@link SprintPlanChain}. Alertas nunca bloqueiam.
 *
 * <p>Linha em sprint_plan_assignments = subtarefa assumida (liberar apaga a linha, junto com as datas). Quem manda na
 * classe é o tipo no Jira (mapeamento da squad); FORA_DA_CONTA e SEM_CLASSIFICACAO não são distribuíveis.
 */
@Service
@RequiredArgsConstructor
public class SprintPlanDistributionService {

    private final SprintPlanRepository planRepository;
    private final SprintPlanItemRepository itemRepository;
    private final SprintPlanAssignmentRepository assignmentRepository;
    private final SprintPlanHolidayRepository holidayRepository;
    private final SprintPlanAbsenceRepository absenceRepository;
    private final SquadMemberRepository squadMemberRepository;
    private final PlannerBacklogService backlogService;
    private final SprintPlanCapacityService capacityService;
    private final SprintPlanWebSocketHandler webSocketHandler;

    /** Quem chama: id do usuário e se é liderança/ADMIN da squad. */
    public record Caller(String userId, boolean leadership) {}

    // ---------------- Leitura ----------------

    @Transactional(readOnly = true)
    public DistributionView view(String squadId, String planId, Caller caller) {
        return buildView(load(squadId, planId, false), caller);
    }

    /** Avisos para o avanço DISTRIBUICAO -> REVISAO (a rota de status continua não bloqueando). */
    @Transactional(readOnly = true)
    public List<DistWarning> warningsFor(String squadId, String planId) {
        return buildView(load(squadId, planId, false), new Caller(null, false)).warnings();
    }

    // ---------------- Escrita ----------------

    @Transactional
    public DistributionView claim(String squadId, String planId, ClaimRequest req, Caller caller) {
        if (req == null || req.subtaskKey() == null || req.subtaskKey().isBlank()) {
            throw unprocessable("Informe a subtarefa.");
        }
        SprintPlan plan = load(squadId, planId, true);
        requireStage(plan);
        Located loc = locate(plan, req.subtaskKey().trim());
        String me = myAccountId(squadId, caller.userId());
        String target = req.accountId() == null || req.accountId().isBlank() ? me : req.accountId().trim();
        if (target == null) {
            throw unprocessable("Você não está vinculado como pessoa desta squad. Informe a pessoa responsável.");
        }
        if (!target.equals(me) && !caller.leadership()) {
            throw forbidden("Só a liderança da squad ou um administrador pode atribuir subtarefas a outra pessoa.");
        }
        String cls = distributableClass(loc.sub());
        if (cls == null) {
            throw unprocessable("Esta subtarefa não entra na distribuição (" + describeNonDistributable(loc.sub()) + ").");
        }
        CapacityRow person = capacityService.compute(plan).people().stream()
                .filter(r -> r.accountId().equals(target)).findFirst()
                .orElseThrow(() -> unprocessable("Essa pessoa não faz parte da squad."));
        if (!person.included()) throw unprocessable(person.name() + " não está incluída neste plano.");
        if (!cls.equals(person.papel())) {
            throw unprocessable(person.name() + " não tem papel " + cls + " nesta sprint; só quem é " + cls
                    + " pega esta subtarefa. Ajuste o papel na capacidade.");
        }
        Optional<SprintPlanAssignment> existing = assignmentRepository.findByPlanIdAndSubtaskKey(planId, loc.sub().key());
        if (existing.isPresent() && existing.get().getJiraAccountId() != null) {
            throw conflict("Essa subtarefa já tem responsável. Recarregue a distribuição.");
        }
        SprintPlanAssignment a = existing.orElseGet(() -> SprintPlanAssignment.builder().id(UUID.randomUUID().toString())
                .planId(planId).itemId(loc.item().getId()).subtaskKey(loc.sub().key()).build());
        a.setJiraAccountId(target);
        a.setAssignmentClass(cls);
        a.setHours(counted(loc.item(), loc.sub()));
        a.setUpdatedBy(caller.userId());
        a.setUpdatedAt(Instant.now());
        try {
            assignmentRepository.saveAndFlush(a);
        } catch (DataIntegrityViolationException e) {
            throw conflict("Essa subtarefa acabou de ser assumida por outra pessoa. Recarregue a distribuição.");
        }
        publishAfterCommit(planId);
        return buildView(plan, caller);
    }

    @Transactional
    public DistributionView release(String squadId, String planId, String subtaskKey, Caller caller) {
        SprintPlan plan = load(squadId, planId, true);
        requireStage(plan);
        Located loc = locate(plan, subtaskKey);
        Optional<SprintPlanAssignment> existing = assignmentRepository.findByPlanIdAndSubtaskKey(planId, loc.sub().key());
        if (existing.isPresent()) {
            SprintPlanAssignment a = existing.get();
            String me = myAccountId(squadId, caller.userId());
            boolean owner = a.getJiraAccountId() != null && a.getJiraAccountId().equals(me);
            if (!owner && !caller.leadership()) {
                throw forbidden("Só quem assumiu a subtarefa, a liderança ou um administrador pode liberá-la.");
            }
            assignmentRepository.delete(a);
            assignmentRepository.flush();
            publishAfterCommit(planId);
        }
        return buildView(plan, caller);
    }

    @Transactional
    public DistributionView setDates(String squadId, String planId, String subtaskKey, DatesRequest req, Caller caller) {
        SprintPlan plan = load(squadId, planId, true);
        requireStage(plan);
        Located loc = locate(plan, subtaskKey);
        SprintPlanAssignment a = assignmentRepository.findByPlanIdAndSubtaskKey(planId, loc.sub().key()).orElse(null);
        String me = myAccountId(squadId, caller.userId());
        boolean owner = a != null && a.getJiraAccountId() != null && a.getJiraAccountId().equals(me);
        if (!owner && !caller.leadership()) {
            throw forbidden("Só quem assumiu a subtarefa, a liderança ou um administrador pode marcar as datas.");
        }
        if (a == null || a.getJiraAccountId() == null) {
            throw unprocessable("Assuma a subtarefa antes de marcar as datas.");
        }
        if (req == null) throw unprocessable("Informe início e fim.");
        LocalDate start = parseDate(req.startDate(), "início");
        LocalDate end = parseDate(req.endDate(), "fim");
        if (end.isBefore(start)) throw unprocessable("O fim não pode ser antes do início.");
        if (start.isBefore(plan.getStartDate()) || end.isAfter(plan.getEndDate())) {
            throw unprocessable("As datas precisam ficar dentro da sprint (" + fmt(plan.getStartDate()) + " a "
                    + fmt(plan.getEndDate()) + ").");
        }
        Set<LocalDate> holidays = holidays(planId);
        Map<String, SprintPlanAssignment> rowByKey = new HashMap<>();
        assignmentRepository.findByPlanId(planId).forEach(r -> rowByKey.put(r.getSubtaskKey(), r));
        List<Node> nodes = chainNodes(loc.parent(), rowByKey);
        Node edited = nodes.stream().filter(n -> n.key.equals(loc.sub().key())).findFirst().orElseThrow();

        LocalDate min = SprintPlanChain.minStart(edited, nodes);
        if (min != null && start.isBefore(min)) {
            String pred = SprintPlanChain.startsAfterKey(edited, nodes);
            throw new DistributionRuleException("O início não pode ser antes de " + fmt(min) + ": "
                    + (edited.level == Level.QA ? "o QA só começa depois do fim do Code Review"
                    : "o Code Review só começa depois do fim da Codificação")
                    + (pred == null ? "" : " (" + pred + ")") + ".", min);
        }
        List<Node> changed = SprintPlanChain.applyWithCascade(nodes, edited, start, end, holidays);
        Instant now = Instant.now();
        List<SprintPlanAssignment> toSave = new ArrayList<>();
        for (Node n : changed) {
            if (n.start.isBefore(plan.getStartDate()) || n.end.isAfter(plan.getEndDate())) {
                throw unprocessable("A mudança empurraria " + n.key + " para fora da sprint ("
                        + fmt(n.start) + " a " + fmt(n.end) + "). Ajuste as datas dentro da sprint.");
            }
            SprintPlanAssignment row = rowByKey.get(n.key);
            row.setStartDate(n.start);
            row.setEndDate(n.end);
            row.setUpdatedBy(caller.userId());
            row.setUpdatedAt(now);
            toSave.add(row);
        }
        assignmentRepository.saveAllAndFlush(toSave);
        publishAfterCommit(planId);
        return buildView(plan, caller);
    }

    // ---------------- Montagem da visão ----------------

    private record Located(SprintPlanItem item, ParentView parent, SubtaskView sub) {}

    DistributionView buildView(SprintPlan plan, Caller caller) {
        String planId = plan.getId();
        PlannerBacklogService.BacklogView backlog = backlogService.view(plan.getSquadId(), plan.sprintKey(), null, false);
        Map<String, ParentView> parents = new HashMap<>();
        backlog.items().forEach(p -> parents.put(p.key(), p));
        List<SprintPlanItem> items = itemRepository.findByPlanIdOrderBySortOrderAsc(planId);
        SprintPlanScopeService.Computed scope = SprintPlanScopeService.compute(items, parents);
        Map<String, SprintPlanAssignment> rowByKey = new HashMap<>();
        assignmentRepository.findByPlanId(planId).forEach(r -> rowByKey.put(r.getSubtaskKey(), r));
        List<SprintPlanHoliday> holidayRows = holidayRepository.findByPlanIdOrderByDayAsc(planId);
        Set<LocalDate> holidays = holidayRows.stream().map(SprintPlanHoliday::getDay)
                .collect(Collectors.toCollection(TreeSet::new));

        CapacityView cap = capacityService.compute(plan);
        Map<String, CapacityRow> peopleRows = new LinkedHashMap<>();
        cap.people().forEach(r -> peopleRows.put(r.accountId(), r));
        String me = myAccountId(plan.getSquadId(), caller.userId());
        CapacityRow myRow = me == null ? null : peopleRows.get(me);
        boolean distributionStage = SprintPlan.STATUS_DISTRIBUICAO.equals(plan.getStatus());

        List<DistTask> tasks = new ArrayList<>();
        Map<String, BigDecimal> allocated = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            SprintPlanItem item = items.get(i);
            ParentView parent = parents.get(item.getParentKey());
            var iv = scope.items().get(i);
            List<DistSubtask> subs = new ArrayList<>();
            if (parent != null) {
                List<Node> nodes = chainNodes(parent, rowByKey);
                Map<String, Node> nodeByKey = new HashMap<>();
                nodes.forEach(n -> nodeByKey.put(n.key, n));
                for (SubtaskView s : parent.subtasks()) {
                    SprintPlanAssignment row = rowByKey.get(s.key());
                    String cls = distributableClass(s);
                    String ownerId = row == null ? null : row.getJiraAccountId();
                    BigDecimal countedH = counted(item, s);
                    Node node = nodeByKey.get(s.key());
                    boolean owned = ownerId != null;
                    if (cls != null && owned) allocated.merge(ownerId, countedH, BigDecimal::add);
                    boolean canClaim = distributionStage && cls != null && !owned
                            && (caller.leadership() || (myRow != null && myRow.included() && cls.equals(myRow.papel())));
                    boolean canEditDates = distributionStage && cls != null && owned
                            && (caller.leadership() || ownerId.equals(me));
                    CapacityRow ownerRow = owned ? peopleRows.get(ownerId) : null;
                    subs.add(new DistSubtask(s.key(), s.title(), s.typeId(), s.typeName(), cls != null ? cls : outsideCode(s),
                            s.codeReview(), s.estimateH(), s.remainingH(), countedH, ownerId,
                            ownerRow != null ? ownerRow.name() : null,
                            row == null ? null : row.getStartDate(), row == null ? null : row.getEndDate(),
                            cls == null || node == null ? null : SprintPlanChain.startsAfterKey(node, nodes),
                            cls == null || node == null ? null : SprintPlanChain.minStart(node, nodes),
                            canClaim, canEditDates, s.status()));
                }
            }
            tasks.add(new DistTask(item.getId(), item.getParentKey(), iv.title(), iv.devHours(), iv.qaHours(), subs));
        }

        Double reserve = plan.getReservePct() == null ? null : plan.getReservePct().doubleValue();
        List<DistPerson> people = new ArrayList<>();
        Map<String, List<AbsenceEntry>> absencesByPerson = new HashMap<>();
        List<SprintPlanAbsence> absenceRows = absenceRepository.findByPlanIdOrderByStartDateAsc(planId);
        if (absenceRows != null) {
            for (SprintPlanAbsence a : absenceRows) {
                absencesByPerson.computeIfAbsent(a.getJiraAccountId(), k -> new ArrayList<>())
                        .add(new AbsenceEntry(a.getStartDate(), a.getEndDate(), a.getHoursPerDay(), a.getReason()));
            }
        }
        for (CapacityRow r : cap.people()) {
            if (!r.included() || !("DEV".equals(r.papel()) || "QA".equals(r.papel()))) continue;
            double alloc = SprintPlanCalculator.round2(allocated.getOrDefault(r.accountId(), BigDecimal.ZERO).doubleValue());
            var t = SprintPlanCalculator.thermometer(alloc, r.capacityHours(), reserve);
            people.add(new DistPerson(r.accountId(), r.name(), r.papel(), r.capacityHours(), alloc,
                    SprintPlanCalculator.round2(r.capacityHours() - alloc), t.pct(), t.zone().name(), t.state().name(),
                    absencesByPerson.getOrDefault(r.accountId(), List.of())));
        }

        List<DistWarning> warnings = warnings(plan, tasks, people, holidays);
        List<HolidayEntry> hol = holidayRows.stream().map(h -> new HolidayEntry(h.getDay(), h.getLabel())).toList();
        return new DistributionView(planId, plan.getStatus(), backlog.syncedAt(), caller.leadership(), me,
                new SprintInfo(plan.getStartDate(), plan.getEndDate(), hol), tasks, people, warnings);
    }

    /** Alertas (nunca bloqueiam). */
    static List<DistWarning> warnings(SprintPlan plan, List<DistTask> tasks, List<DistPerson> people, Set<LocalDate> holidays) {
        List<DistWarning> w = new ArrayList<>();
        for (DistPerson p : people) {
            if (p.pct() != null && p.pct() > 100.0) {
                boolean covered = "COBERTO_PELA_RESERVA".equals(p.state());
                w.add(new DistWarning("PERSON_OVER_CAPACITY", p.name() + " está com " + fmtH(p.allocatedHours()) + " h para "
                        + fmtH(p.capacityHours()) + " h de capacidade (" + Math.round(p.pct()) + "%)"
                        + (covered ? ", coberto pela reserva." : ", acima da reserva."), null, p.accountId()));
            }
        }
        for (DistTask t : tasks) {
            for (DistSubtask s : t.subtasks()) {
                if (!"DEV".equals(s.cls()) && !"QA".equals(s.cls())) continue;
                if (s.assigneeId() == null) {
                    w.add(new DistWarning("SUBTASK_UNASSIGNED", s.key() + " (" + s.typeName() + ") ainda não tem responsável.",
                            s.key(), null));
                    continue;
                }
                if (s.startDate() == null || s.endDate() == null) {
                    w.add(new DistWarning("SUBTASK_NO_DATES", s.key() + " (" + s.typeName() + ") está sem datas.",
                            s.key(), s.assigneeId()));
                    continue;
                }
                if (s.startDate().isBefore(plan.getStartDate()) || s.endDate().isAfter(plan.getEndDate())) {
                    w.add(new DistWarning("DATES_OUTSIDE_SPRINT", s.key() + " tem datas fora da sprint.", s.key(), s.assigneeId()));
                }
                if (holidays.contains(s.startDate()) || holidays.contains(s.endDate())) {
                    w.add(new DistWarning("TASK_ON_HOLIDAY", s.key() + " começa ou termina em feriado.", s.key(), s.assigneeId()));
                }
                if (s.minStartDate() != null && s.startDate().isBefore(s.minStartDate())) {
                    boolean qa = "QA".equals(s.cls());
                    w.add(new DistWarning(qa ? "QA_BEFORE_DEV" : "CODE_REVIEW_BEFORE_CODING",
                            s.key() + (qa ? " (QA) começa antes do fim do Code Review/Codificação"
                                    : " (Code Review) começa antes do fim da Codificação") + " (" + fmt(s.minStartDate()) + ").",
                            s.key(), s.assigneeId()));
                }
            }
        }
        return w;
    }

    // ---------------- Apoio ----------------

    private List<Node> chainNodes(ParentView parent, Map<String, SprintPlanAssignment> rowByKey) {
        List<Node> nodes = new ArrayList<>();
        for (SubtaskView s : parent.subtasks()) {
            String cls = distributableClass(s);
            if (cls == null) continue;
            SprintPlanAssignment row = rowByKey.get(s.key());
            boolean owned = row != null && row.getJiraAccountId() != null;
            nodes.add(new Node(s.key(), SprintPlanChain.levelOf(cls, s.codeReview()),
                    owned ? row.getStartDate() : null, owned ? row.getEndDate() : null));
        }
        return nodes;
    }

    private Located locate(SprintPlan plan, String subtaskKey) {
        PlannerBacklogService.BacklogView backlog = backlogService.view(plan.getSquadId(), plan.sprintKey(), null, false);
        Map<String, ParentView> parents = new HashMap<>();
        backlog.items().forEach(p -> parents.put(p.key(), p));
        for (SprintPlanItem item : itemRepository.findByPlanIdOrderBySortOrderAsc(plan.getId())) {
            ParentView p = parents.get(item.getParentKey());
            if (p == null) continue;
            for (SubtaskView s : p.subtasks()) {
                if (s.key().equals(subtaskKey)) return new Located(item, p, s);
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Subtarefa não faz parte deste plano.");
    }

    static String distributableClass(SubtaskView s) {
        return switch (s.subtaskClass()) {
            case DEV -> "DEV";
            case QA -> "QA";
            default -> null;
        };
    }

    private static String outsideCode(SubtaskView s) {
        return s.subtaskClass() == SubtaskClass.FORA_DA_CONTA ? "FORA" : "SEM";
    }

    private static String describeNonDistributable(SubtaskView s) {
        return s.subtaskClass() == SubtaskClass.FORA_DA_CONTA ? "tipo fora da conta"
                : "tipo sem classificação, mapeie o tipo na capacidade";
    }

    /** Horas que a subtarefa pesa: estimado; restante quando a pai é sobra (carry-over). */
    private static BigDecimal counted(SprintPlanItem item, SubtaskView s) {
        return item.isCarryOver() ? s.remainingH() : s.estimateH();
    }

    private String myAccountId(String squadId, String userId) {
        if (userId == null) return null;
        return squadMemberRepository.findByClaimedByUid(userId).stream()
                .filter(m -> squadId.equals(m.getSquadId()))
                .map(SquadMember::getJiraAccountId).findFirst().orElse(null);
    }

    private Set<LocalDate> holidays(String planId) {
        return holidayRepository.findByPlanIdOrderByDayAsc(planId).stream().map(SprintPlanHoliday::getDay)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private SprintPlan load(String squadId, String planId, boolean forUpdate) {
        Optional<SprintPlan> p = forUpdate ? planRepository.findByIdForUpdate(planId) : planRepository.findById(planId);
        return p.filter(x -> x.getSquadId().equals(squadId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plano não encontrado."));
    }

    private static void requireStage(SprintPlan plan) {
        if (!SprintPlan.STATUS_DISTRIBUICAO.equals(plan.getStatus())) {
            throw unprocessable("A distribuição só pode ser alterada na etapa de Distribuição.");
        }
    }

    private void publishAfterCommit(String planId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    webSocketHandler.broadcastRefresh(planId);
                }
            });
        } else {
            webSocketHandler.broadcastRefresh(planId);
        }
    }

    /** Aceita yyyy-MM-dd ou data-hora ISO (como o cronograma refeito guarda); vale só a parte da data. */
    static LocalDate parseDate(String raw, String label) {
        String n = JiraDateParser.normalize(raw);
        if (n.isEmpty()) throw unprocessable("Data de " + label + " inválida: use o formato aaaa-mm-dd.");
        try {
            return LocalDate.parse(n.substring(0, 10));
        } catch (RuntimeException e) {
            throw unprocessable("Data de " + label + " inválida: use o formato aaaa-mm-dd.");
        }
    }

    private static String fmt(LocalDate d) {
        return d.format(DateTimeFormatter.ofPattern("dd/MM"));
    }

    private static String fmtH(double h) {
        return (h == Math.rint(h) ? String.valueOf((long) h) : String.valueOf(h)).replace('.', ',');
    }

    private static ResponseStatusException unprocessable(String msg) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
    }

    private static ResponseStatusException conflict(String msg) {
        return new ResponseStatusException(HttpStatus.CONFLICT, msg);
    }

    private static ResponseStatusException forbidden(String msg) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, msg);
    }

    /** 422 da cadeia, com o menor início permitido para o cliente ajustar o campo. */
    public static class DistributionRuleException extends ResponseStatusException {
        private final LocalDate minStartDate;

        public DistributionRuleException(String message, LocalDate minStartDate) {
            super(HttpStatus.UNPROCESSABLE_ENTITY, message);
            this.minStartDate = minStartDate;
        }

        public LocalDate minStartDate() {
            return minStartDate;
        }
    }
}
