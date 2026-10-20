package com.agilespace.backend.service.sprintplan;

import com.agilespace.backend.domain.SprintPlan;
import com.agilespace.backend.domain.SprintPlanItem;
import com.agilespace.backend.domain.SubtaskClass;
import com.agilespace.backend.dto.SprintPlanDtos.*;
import com.agilespace.backend.repository.SprintPlanItemRepository;
import com.agilespace.backend.repository.SprintPlanRepository;
import com.agilespace.backend.service.PlannerBacklogService;
import com.agilespace.backend.service.PlannerBacklogService.ParentView;
import com.agilespace.backend.service.PlannerBacklogService.SubtaskView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Escopo do plano (E3): pais do Jira escolhidas do backlog refinado (ou sobra da sprint anterior) e as horas que
 * entram na conta. Autorização fica no controller; aqui: 404 plano de outra squad, 409 duplicada, 422 etapa/regra.
 *
 * <p>HORAS_ITEM (plano técnico, seção 5): sobra conta o RESTANTE das subtarefas da classe; item novo vale o TÍTULO
 * "(DEV xh / QA yh)" (subtarefas só geram alerta de divergência); sem horas no título, soma das subtarefas da classe.
 * Tipos FORA_DA_CONTA e SEM_CLASSIFICACAO nunca entram no total; os sem classificação geram aviso (quantidade e horas).
 */
@Service
@RequiredArgsConstructor
public class SprintPlanScopeService {

    private static final Set<String> SCOPE_EDITABLE = Set.of(SprintPlan.STATUS_RASCUNHO, SprintPlan.STATUS_ESCOPO);

    private final SprintPlanRepository planRepository;
    private final SprintPlanItemRepository itemRepository;
    private final PlannerBacklogService backlogService;
    private final SprintPlanCapacityService capacityService;

    /** Itens + totais calculados. */
    public record Computed(List<ItemView> items, ScopeTotals totals) {}

    // ---------------- Leitura ----------------

    @Transactional(readOnly = true)
    public ScopeView view(String squadId, String planId, boolean canEdit) {
        return buildView(load(squadId, planId), canEdit);
    }

    @Transactional(readOnly = true)
    public ScopeView buildView(SprintPlan plan, boolean canEdit) {
        PlannerBacklogService.BacklogView backlog = backlogService.view(plan.getSquadId(), plan.sprintKey(), null, false);
        Computed c = compute(itemRepository.findByPlanIdOrderBySortOrderAsc(plan.getId()), index(backlog));
        CapacityView cap = capacityService.compute(plan);
        return new ScopeView(plan.getId(), plan.getStatus(), backlog.syncedAt(), c.items(), c.totals(),
                capacityService.thermometer(plan, cap, c.totals().allocated()), canEdit);
    }

    /** Itens e totais do plano (usado pelo PlanView e pela lista de planos). */
    @Transactional(readOnly = true)
    public Computed compute(SprintPlan plan) {
        PlannerBacklogService.BacklogView backlog = backlogService.view(plan.getSquadId(), plan.sprintKey(), null, false);
        return compute(itemRepository.findByPlanIdOrderBySortOrderAsc(plan.getId()), index(backlog));
    }

    private static Map<String, ParentView> index(PlannerBacklogService.BacklogView backlog) {
        Map<String, ParentView> m = new HashMap<>();
        for (ParentView p : backlog.items()) m.put(p.key(), p);
        return m;
    }

    // ---------------- Escrita ----------------

    @Transactional
    public ScopeView addItems(String squadId, String planId, AddItemsRequest req, String userId, boolean canEdit) {
        if (req == null) throw unprocessable("Informe a tarefa a incluir.");
        SprintPlan plan = load(squadId, planId);
        requireScopeStage(plan);
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (req.parentKey() != null && !req.parentKey().isBlank()) keys.add(req.parentKey().trim());
        if (req.parentKeys() != null) {
            req.parentKeys().stream().filter(k -> k != null && !k.isBlank()).map(String::trim).forEach(keys::add);
        }
        if (keys.isEmpty()) throw unprocessable("Informe a tarefa a incluir.");

        Map<String, ParentView> backlog = index(backlogService.view(squadId, plan.sprintKey(), null, false));
        Set<String> inPlan = new HashSet<>();
        int next = 0;
        for (SprintPlanItem i : itemRepository.findByPlanIdOrderBySortOrderAsc(planId)) {
            inPlan.add(i.getParentKey());
            next = Math.max(next, i.getSortOrder() + 1);
        }
        for (String k : keys) {
            if (inPlan.contains(k)) throw conflict(k + " já está no escopo desta sprint.");
            if (!backlog.containsKey(k)) {
                throw unprocessable(k + " não está no backlog refinado nem é sobra da sprint anterior. Sincronize o backlog e tente de novo.");
            }
        }
        Instant now = Instant.now();
        for (String k : keys) {
            ParentView p = backlog.get(k);
            itemRepository.save(SprintPlanItem.builder()
                    .id(UUID.randomUUID().toString()).planId(planId).parentKey(k).titleSnapshot(p.title())
                    .titleDevHours(p.titleHours() == null ? null : p.titleHours().dev())
                    .titleQaHours(p.titleHours() == null ? null : p.titleHours().qa())
                    .sortOrder(next++).carryOver(p.carryOver()).addedBy(userId).addedAt(now).build());
        }
        itemRepository.flush();
        touch(plan);
        return buildView(plan, canEdit);
    }

    @Transactional
    public void removeItem(String squadId, String planId, String itemId) {
        SprintPlan plan = load(squadId, planId);
        requireScopeStage(plan);
        SprintPlanItem item = itemRepository.findById(itemId).filter(i -> i.getPlanId().equals(planId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item não encontrado."));
        itemRepository.delete(item);
        itemRepository.flush();
        touch(plan);
    }

    @Transactional
    public ScopeView reorder(String squadId, String planId, ReorderItemsRequest req, boolean canEdit) {
        SprintPlan plan = load(squadId, planId);
        requireScopeStage(plan);
        List<SprintPlanItem> current = itemRepository.findByPlanIdOrderBySortOrderAsc(planId);
        List<String> ordered = req == null || req.orderedItemIds() == null ? List.of() : req.orderedItemIds();
        Map<String, SprintPlanItem> byId = new HashMap<>();
        current.forEach(i -> byId.put(i.getId(), i));
        if (ordered.size() != current.size() || !byId.keySet().equals(new HashSet<>(ordered))) {
            throw unprocessable("A nova ordem precisa conter todos os itens do escopo, sem repetir.");
        }
        for (int idx = 0; idx < ordered.size(); idx++) byId.get(ordered.get(idx)).setSortOrder(idx);
        itemRepository.saveAllAndFlush(current);
        touch(plan);
        return buildView(plan, canEdit);
    }

    /** ESCOPO -> DISTRIBUICAO exige ao menos um item; divergência e tipos sem classificação só avisam. */
    public void requireItemsToLeaveScope(String planId) {
        if (itemRepository.countByPlanId(planId) == 0) {
            throw unprocessable("Inclua ao menos uma tarefa no escopo antes de ir para a distribuição.");
        }
    }

    // ---------------- Cálculo (puro) ----------------

    public static Computed compute(List<SprintPlanItem> items, Map<String, ParentView> backlog) {
        List<ItemView> views = new ArrayList<>();
        double allocDev = 0, allocQa = 0, carryDev = 0, carryQa = 0, unclassHours = 0;
        int carryCount = 0, divergent = 0, noEstimate = 0, unclassCount = 0;
        Map<String, UnclassifiedTypeView> types = new LinkedHashMap<>();
        for (SprintPlanItem it : items) {
            ParentView p = backlog.get(it.getParentKey());
            ItemView v = itemView(it, p);
            views.add(v);
            allocDev += v.devHours().doubleValue();
            allocQa += v.qaHours().doubleValue();
            if (v.carryOver()) {
                carryCount++;
                carryDev += v.devHours().doubleValue();
                carryQa += v.qaHours().doubleValue();
            }
            if (v.divergent()) divergent++;
            if (v.noEstimate()) noEstimate++;
            if (p == null) continue;
            for (SubtaskView s : p.subtasks()) {
                if (s.subtaskClass() != SubtaskClass.SEM_CLASSIFICACAO) continue;
                BigDecimal counted = it.isCarryOver() ? s.remainingH() : s.estimateH();
                unclassCount++;
                unclassHours += counted.doubleValue();
                String key = s.typeId() != null ? s.typeId() : "nome:" + s.typeName();
                UnclassifiedTypeView prev = types.get(key);
                types.put(key, new UnclassifiedTypeView(s.typeId(), s.typeName(),
                        (prev == null ? 0 : prev.count()) + 1,
                        (prev == null ? BigDecimal.ZERO : prev.hours()).add(counted)));
            }
        }
        Map<String, Double> alloc = new LinkedHashMap<>();
        alloc.put(SprintPlanCapacityService.CLASS_DEV, SprintPlanCalculator.round2(allocDev));
        alloc.put(SprintPlanCapacityService.CLASS_QA, SprintPlanCalculator.round2(allocQa));
        ScopeTotals totals = new ScopeTotals(views.size(), alloc, carryCount, SprintPlanCalculator.round2(carryDev),
                SprintPlanCalculator.round2(carryQa), divergent, noEstimate, unclassCount,
                SprintPlanCalculator.round2(unclassHours), new ArrayList<>(types.values()));
        return new Computed(views, totals);
    }

    static ItemView itemView(SprintPlanItem it, ParentView p) {
        boolean carry = it.isCarryOver();
        // Horas do título: o que o Jira diz agora; se a pai saiu do backlog, vale o que foi gravado na inclusão.
        BigDecimal titleDev = p != null ? (p.titleHours() == null ? null : p.titleHours().dev()) : it.getTitleDevHours();
        BigDecimal titleQa = p != null ? (p.titleHours() == null ? null : p.titleHours().qa()) : it.getTitleQaHours();
        boolean hasTitle = titleDev != null || titleQa != null;

        BigDecimal devEst = BigDecimal.ZERO, devRem = BigDecimal.ZERO, qaEst = BigDecimal.ZERO, qaRem = BigDecimal.ZERO;
        BigDecimal unclassH = BigDecimal.ZERO;
        int unclassN = 0;
        List<ScopeSubtaskView> subs = new ArrayList<>();
        if (p != null) {
            for (SubtaskView s : p.subtasks()) {
                if (s.subtaskClass() == SubtaskClass.DEV) {
                    devEst = devEst.add(s.estimateH());
                    devRem = devRem.add(s.remainingH());
                } else if (s.subtaskClass() == SubtaskClass.QA) {
                    qaEst = qaEst.add(s.estimateH());
                    qaRem = qaRem.add(s.remainingH());
                } else if (s.subtaskClass() == SubtaskClass.SEM_CLASSIFICACAO) {
                    unclassN++;
                    unclassH = unclassH.add(carry ? s.remainingH() : s.estimateH());
                }
                subs.add(new ScopeSubtaskView(s.key(), s.title(), s.typeId(), s.typeName(), s.subtaskClass().name(),
                        s.estimateH(), s.remainingH(), carry ? s.remainingH() : s.estimateH(), s.defaultHoursApplied(),
                        s.assigneeId(), s.assigneeName()));
            }
        }
        BigDecimal subDev = carry ? devRem : devEst;
        BigDecimal subQa = carry ? qaRem : qaEst;

        BigDecimal dev, qa;
        if (carry) {
            dev = devRem;
            qa = qaRem;
        } else if (hasTitle) {
            dev = titleDev == null ? BigDecimal.ZERO : titleDev;
            qa = titleQa == null ? BigDecimal.ZERO : titleQa;
        } else {
            dev = devEst;
            qa = qaEst;
        }
        boolean divDev = PlannerBacklogService.isDivergent(carry, titleDev, devEst);
        boolean divQa = PlannerBacklogService.isDivergent(carry, titleQa, qaEst);
        // Sem estimativa: nada no título e nenhuma subtarefa DEV/QA estimada (sobra: nada restante nem estimado).
        boolean noEst = !hasTitle && devEst.signum() == 0 && qaEst.signum() == 0
                && (!carry || (devRem.signum() == 0 && qaRem.signum() == 0));
        return new ItemView(it.getId(), it.getParentKey(), p != null ? p.title() : it.getTitleSnapshot(), titleDev, titleQa,
                it.getSortOrder(), carry, dev, qa, subDev, subQa, devEst, devRem, qaEst, qaRem,
                divDev, divQa, divDev || divQa, noEst, p != null, unclassN, unclassH, subs);
    }

    // ---------------- Apoio ----------------

    private SprintPlan load(String squadId, String planId) {
        return planRepository.findById(planId).filter(p -> p.getSquadId().equals(squadId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plano não encontrado."));
    }

    private static void requireScopeStage(SprintPlan plan) {
        if (!SCOPE_EDITABLE.contains(plan.getStatus())) {
            throw unprocessable("O escopo só pode ser alterado até a etapa de Escopo. Volte o plano para Escopo para mudar as tarefas.");
        }
    }

    private void touch(SprintPlan plan) {
        plan.setUpdatedAt(Instant.now());
        planRepository.saveAndFlush(plan);
    }

    private static ResponseStatusException unprocessable(String msg) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
    }

    private static ResponseStatusException conflict(String msg) {
        return new ResponseStatusException(HttpStatus.CONFLICT, msg);
    }
}
