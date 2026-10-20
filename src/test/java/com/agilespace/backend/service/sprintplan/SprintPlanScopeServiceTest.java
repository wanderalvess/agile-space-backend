package com.agilespace.backend.service.sprintplan;

import com.agilespace.backend.domain.SprintPlan;
import com.agilespace.backend.domain.SprintPlanItem;
import com.agilespace.backend.domain.SubtaskClass;
import com.agilespace.backend.dto.SprintPlanDtos.*;
import com.agilespace.backend.repository.SprintPlanItemRepository;
import com.agilespace.backend.repository.SprintPlanRepository;
import com.agilespace.backend.service.PlannerBacklogService;
import com.agilespace.backend.service.PlannerBacklogService.HoursPair;
import com.agilespace.backend.service.PlannerBacklogService.ParentView;
import com.agilespace.backend.service.PlannerBacklogService.SubtaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SprintPlanScopeService - escopo, HORAS_ITEM e termômetro")
class SprintPlanScopeServiceTest {

    @Mock SprintPlanRepository planRepository;
    @Mock SprintPlanItemRepository itemRepository;
    @Mock PlannerBacklogService backlogService;
    @Mock SprintPlanCapacityService capacityService;

    SprintPlanScopeService service;
    SprintPlan plan;
    Map<String, ParentView> backlog = new LinkedHashMap<>();
    List<SprintPlanItem> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new SprintPlanScopeService(planRepository, itemRepository, backlogService, capacityService);
        plan = SprintPlan.builder().id("P1").squadId("SQ1").jiraSprintId("S3").sprintName("Sprint 3")
                .startDate(LocalDate.of(2026, 11, 2)).endDate(LocalDate.of(2026, 11, 13))
                .status(SprintPlan.STATUS_ESCOPO).reserveMode(SprintPlan.RESERVE_HORAS)
                .reservePct(new BigDecimal("12")).version(1L).build();
        lenient().when(planRepository.findById("P1")).thenReturn(Optional.of(plan));
        lenient().when(planRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(backlogService.view(eq("SQ1"), any(), any(), eq(false))).thenAnswer(i ->
                new PlannerBacklogService.BacklogView("2026-10-20T10:00:00Z", "S3", new ArrayList<>(backlog.values()), List.of()));
        lenient().when(itemRepository.findByPlanIdOrderBySortOrderAsc("P1")).thenAnswer(i -> new ArrayList<>(stored));
        lenient().when(itemRepository.save(any())).thenAnswer(i -> {
            stored.add(i.getArgument(0));
            return i.getArgument(0);
        });
        lenient().when(capacityService.compute(any())).thenReturn(new CapacityView(Map.of("DEV", 90.0, "QA", 75.0), List.of(), 10));
    }

    private static BigDecimal h(String v) {
        return new BigDecimal(v);
    }

    private static SubtaskView sub(String key, SubtaskClass cls, String est, String rem) {
        return new SubtaskView(key, key, "t", "Tipo", cls, h(est), h(rem), h(est), false, "Aberto", "new", null, null);
    }

    private static ParentView parent(String key, String devTitle, String qaTitle, boolean carry, SubtaskView... subs) {
        Map<SubtaskClass, BigDecimal> sums = new EnumMap<>(SubtaskClass.class);
        for (SubtaskClass c : SubtaskClass.values()) sums.put(c, BigDecimal.ZERO);
        return new ParentView(key, "Título " + key, "Refinamento Concluído", carry,
                devTitle == null ? null : new HoursPair(h(devTitle), h(qaTitle)), List.of(subs), sums, false, false, false);
    }

    private static SprintPlanItem item(String key, int order, boolean carry, ParentView p) {
        return SprintPlanItem.builder().id("I-" + key).planId("P1").parentKey(key).titleSnapshot(p.title())
                .titleDevHours(p.titleHours() == null ? null : p.titleHours().dev())
                .titleQaHours(p.titleHours() == null ? null : p.titleHours().qa())
                .sortOrder(order).carryOver(carry).addedBy("u").addedAt(Instant.now()).build();
    }

    private SprintPlanScopeService.Computed computeOf(ParentView... parents) {
        List<SprintPlanItem> items = new ArrayList<>();
        Map<String, ParentView> idx = new HashMap<>();
        int n = 0;
        for (ParentView p : parents) {
            items.add(item(p.key(), n++, p.carryOver(), p));
            idx.put(p.key(), p);
        }
        return SprintPlanScopeService.compute(items, idx);
    }

    // ---- cálculo e termômetro ----

    @Test
    @DisplayName("DEV 90 h de capacidade: alocado 90 = 100%, dentro da capacidade")
    void dev90Exact() {
        var c = computeOf(parent("A-1", "50", "10", false), parent("A-2", "40", "20", false));
        assertEquals(90.0, c.totals().allocated().get("DEV"));
        var t = SprintPlanCalculator.thermometer(c.totals().allocated().get("DEV"), 90, 12.0);
        assertEquals(SprintPlanCalculator.State.DENTRO_DA_CAPACIDADE, t.state());
        assertEquals(100.0, t.pct());
    }

    @Test
    @DisplayName("DEV 96 de 90 (107%) fica coberto pela reserva de 12%; 120 de 90 fica acima da reserva")
    void reserveCoverage() {
        var covered = computeOf(parent("A-1", "96", "0", false));
        var t1 = SprintPlanCalculator.thermometer(covered.totals().allocated().get("DEV"), 90, 12.0);
        assertEquals(106.67, t1.pct(), 0.01);
        assertEquals(SprintPlanCalculator.State.COBERTO_PELA_RESERVA, t1.state());

        var over = computeOf(parent("A-1", "120", "0", false));
        var t2 = SprintPlanCalculator.thermometer(over.totals().allocated().get("DEV"), 90, 12.0);
        assertEquals(SprintPlanCalculator.State.ACIMA_DA_RESERVA, t2.state());
    }

    @Test
    @DisplayName("QA 70 de 75: 93,3% amarelo, dentro da capacidade")
    void qa70of75() {
        var c = computeOf(parent("A-1", "10", "40", false), parent("A-2", "10", "30", false));
        var t = SprintPlanCalculator.thermometer(c.totals().allocated().get("QA"), 75, 12.0);
        assertEquals(70.0, c.totals().allocated().get("QA"));
        assertEquals(SprintPlanCalculator.Zone.AMARELO, t.zone());
        assertEquals(SprintPlanCalculator.State.DENTRO_DA_CAPACIDADE, t.state());
    }

    @Test
    @DisplayName("Vale o título; subtarefas só alertam: título 18 h x Codificação 16 h = divergência, total 18")
    void titleWinsAndDivergence() {
        var c = computeOf(parent("A-1", "18", "14", false,
                sub("S-1", SubtaskClass.DEV, "16", "16"), sub("S-2", SubtaskClass.QA, "14", "14")));
        ItemView v = c.items().get(0);
        assertEquals(h("18"), v.devHours());
        assertEquals(h("14"), v.qaHours());
        assertTrue(v.divergenceDev());
        assertFalse(v.divergenceQa());
        assertTrue(v.divergent());
        assertEquals(h("16"), v.subtaskDevH());
        assertEquals(1, c.totals().divergentCount());
        assertEquals(18.0, c.totals().allocated().get("DEV"));
    }

    @Test
    @DisplayName("Carry-over conta o RESTANTE das subtarefas e não gera divergência")
    void carryOverUsesRemaining() {
        var c = computeOf(parent("A-1", "8", "6", true,
                sub("S-1", SubtaskClass.DEV, "14", "8"), sub("S-2", SubtaskClass.QA, "6", "6")));
        ItemView v = c.items().get(0);
        assertTrue(v.carryOver());
        assertEquals(h("8"), v.devHours());
        assertEquals(h("6"), v.qaHours());
        assertEquals(h("14"), v.subtaskDevEstimateH());
        assertEquals(h("8"), v.subtaskDevRemainingH());
        assertFalse(v.divergent());
        assertEquals(1, c.totals().carryOverCount());
        assertEquals(8.0, c.totals().carryOverDevHours());
    }

    @Test
    @DisplayName("Sem horas no título: soma das subtarefas da classe; sem nada estimado: sinalizado")
    void noTitleAndNoEstimate() {
        var c = computeOf(
                parent("A-1", null, null, false, sub("S-1", SubtaskClass.DEV, "5", "5"), sub("S-2", SubtaskClass.QA, "3", "3")),
                parent("A-2", null, null, false, sub("S-3", SubtaskClass.DEV, "0", "0")),
                parent("A-3", null, null, false));
        assertEquals(h("5"), c.items().get(0).devHours());
        assertFalse(c.items().get(0).noEstimate());
        assertTrue(c.items().get(1).noEstimate());
        assertTrue(c.items().get(2).noEstimate());
        assertEquals(2, c.totals().noEstimateCount());
        assertEquals(5.0, c.totals().allocated().get("DEV"));
    }

    @Test
    @DisplayName("Tipo sem classificação não entra no total e gera aviso (quantidade e horas); fora da conta fica de fora")
    void unclassifiedAndOutOfAccount() {
        SubtaskView spec = new SubtaskView("S-9", "Spec", "777", "Especificação", SubtaskClass.SEM_CLASSIFICACAO,
                h("3"), h("3"), h("3"), false, "Aberto", "new", null, null);
        var c = computeOf(parent("A-1", null, null, false, sub("S-1", SubtaskClass.DEV, "11", "11"), spec,
                sub("S-8", SubtaskClass.FORA_DA_CONTA, "4", "4")));
        assertEquals(11.0, c.totals().allocated().get("DEV"));
        assertEquals(0.0, c.totals().allocated().get("QA"));
        assertEquals(1, c.totals().unclassifiedCount());
        assertEquals(3.0, c.totals().unclassifiedHours());
        assertEquals("Especificação", c.totals().unclassifiedTypes().get(0).typeName());
        assertEquals(1, c.items().get(0).unclassifiedCount());
    }

    @Test
    @DisplayName("Pai que saiu do backlog usa as horas gravadas na inclusão")
    void parentGoneFallsBackToSnapshot() {
        ParentView p = parent("A-1", "10", "4", false);
        var c = SprintPlanScopeService.compute(List.of(item("A-1", 0, false, p)), Map.of());
        assertEquals(h("10"), c.items().get(0).devHours());
        assertFalse(c.items().get(0).inBacklog());
    }

    @Test
    @DisplayName("view monta o termômetro com a capacidade do plano e a reserva de 12%")
    void viewBuildsThermometer() {
        backlog.put("A-1", parent("A-1", "96", "70", false));
        stored.add(item("A-1", 0, false, backlog.get("A-1")));
        when(capacityService.thermometer(any(), any(), any())).thenAnswer(i -> {
            Map<String, Double> alloc = i.getArgument(2);
            return Map.of("DEV", SprintPlanCalculator.thermometer(alloc.get("DEV"), 90, 12.0));
        });
        ScopeView v = service.view("SQ1", "P1", true);
        assertEquals(SprintPlanCalculator.State.COBERTO_PELA_RESERVA, v.thermometer().get("DEV").state());
        assertEquals("2026-10-20T10:00:00Z", v.syncedAt());
        assertEquals(1, v.items().size());
    }

    // ---- escrita ----

    @Test
    @DisplayName("Adicionar pai do backlog: grava horas do título e carry-over; duplicada 409; fora do backlog 422")
    void addItems() {
        backlog.put("A-1", parent("A-1", "10", "4", false));
        backlog.put("A-2", parent("A-2", "8", "6", true));
        service.addItems("SQ1", "P1", new AddItemsRequest(null, List.of("A-1", "A-2")), "lead", true);
        assertEquals(2, stored.size());
        assertEquals(h("10"), stored.get(0).getTitleDevHours());
        assertTrue(stored.get(1).isCarryOver());
        assertEquals(1, stored.get(1).getSortOrder());

        var dup = assertThrows(ResponseStatusException.class,
                () -> service.addItems("SQ1", "P1", new AddItemsRequest("A-1", null), "lead", true));
        assertEquals(HttpStatus.CONFLICT, dup.getStatusCode());
        var out = assertThrows(ResponseStatusException.class,
                () -> service.addItems("SQ1", "P1", new AddItemsRequest("Z-9", null), "lead", true));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, out.getStatusCode());
        assertEquals(2, stored.size());
    }

    @Test
    @DisplayName("Etapa depois de ESCOPO: adicionar, remover e reordenar dão 422")
    void wrongStage() {
        plan.setStatus(SprintPlan.STATUS_DISTRIBUICAO);
        backlog.put("A-1", parent("A-1", "10", "4", false));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
                () -> service.addItems("SQ1", "P1", new AddItemsRequest("A-1", null), "lead", true)).getStatusCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
                () -> service.removeItem("SQ1", "P1", "I-A-1")).getStatusCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
                () -> service.reorder("SQ1", "P1", new ReorderItemsRequest(List.of()), true)).getStatusCode());
        verify(itemRepository, never()).save(any());
    }

    @Test
    @DisplayName("Reordenar exige todos os itens, sem repetir; reordena de verdade")
    void reorder() {
        ParentView a = parent("A-1", "1", "1", false);
        ParentView b = parent("A-2", "1", "1", false);
        backlog.put("A-1", a);
        backlog.put("A-2", b);
        stored.add(item("A-1", 0, false, a));
        stored.add(item("A-2", 1, false, b));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
                () -> service.reorder("SQ1", "P1", new ReorderItemsRequest(List.of("I-A-1")), true)).getStatusCode());
        service.reorder("SQ1", "P1", new ReorderItemsRequest(List.of("I-A-2", "I-A-1")), true);
        assertEquals(0, stored.get(1).getSortOrder());
        assertEquals(1, stored.get(0).getSortOrder());
    }

    @Test
    @DisplayName("Plano de outra squad: 404; avanço sem itens: 422 com mensagem; com itens: ok")
    void otherSquadAndLeaveScope() {
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.view("OUTRA", "P1", false)).getStatusCode());
        when(itemRepository.countByPlanId("P1")).thenReturn(0L);
        var ex = assertThrows(ResponseStatusException.class, () -> service.requireItemsToLeaveScope("P1"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        assertTrue(ex.getReason().contains("ao menos uma tarefa"));
        when(itemRepository.countByPlanId("P1")).thenReturn(2L);
        assertDoesNotThrow(() -> service.requireItemsToLeaveScope("P1"));
    }
}
