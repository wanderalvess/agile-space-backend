package com.agilespace.backend.service.sprintplan;

import com.agilespace.backend.domain.*;
import com.agilespace.backend.dto.SprintPlanDistributionDtos.*;
import com.agilespace.backend.dto.SprintPlanDtos.CapacityRow;
import com.agilespace.backend.dto.SprintPlanDtos.CapacityView;
import com.agilespace.backend.repository.*;
import com.agilespace.backend.service.PlannerBacklogService;
import com.agilespace.backend.service.PlannerBacklogService.ParentView;
import com.agilespace.backend.service.PlannerBacklogService.SubtaskView;
import com.agilespace.backend.service.sprintplan.SprintPlanDistributionService.Caller;
import com.agilespace.backend.service.sprintplan.SprintPlanDistributionService.DistributionRuleException;
import com.agilespace.backend.websocket.SprintPlanWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SprintPlanDistributionService - claim, cadeia, cascata, saldo e alertas")
class SprintPlanDistributionServiceTest {

    @Mock SprintPlanRepository planRepository;
    @Mock SprintPlanItemRepository itemRepository;
    @Mock SprintPlanAssignmentRepository assignmentRepository;
    @Mock SprintPlanHolidayRepository holidayRepository;
    @Mock SquadMemberRepository memberRepository;
    @Mock PlannerBacklogService backlogService;
    @Mock SprintPlanCapacityService capacityService;
    @Mock SprintPlanWebSocketHandler ws;

    SprintPlanDistributionService service;
    SprintPlan plan;
    SprintPlanItem item;
    final Map<String, SprintPlanAssignment> store = new ConcurrentHashMap<>();
    final List<SprintPlanHoliday> holidays = new ArrayList<>();
    List<SubtaskView> subtasks;
    boolean carry = false;
    final List<CapacityRow> people = new ArrayList<>();

    static final Caller ANA = new Caller("u-ana", false);
    static final Caller BRUNO = new Caller("u-bruno", false);
    static final Caller DIEGO = new Caller("u-diego", false);
    static final Caller LEAD = new Caller("u-lead", true);

    static LocalDate d(int day) {
        return LocalDate.of(2026, 11, day);
    }

    static BigDecimal h(String v) {
        return new BigDecimal(v);
    }

    static SubtaskView sub(String key, SubtaskClass cls, boolean cr, String est, String rem) {
        return new SubtaskView(key, "Sub " + key, "t-" + key, "Tipo " + key, cls, h(est), h(rem), h(est), false,
                "Aberto", "new", null, null, cr);
    }

    static CapacityRow person(String acc, String name, String papel, double cap) {
        return new CapacityRow(acc, name, papel, 10, 0, 8.0, false, false, false, false, "x", "x", "x", "x",
                true, null, null, cap, 0, 0, cap);
    }

    void backlog() {
        Map<SubtaskClass, BigDecimal> sums = new EnumMap<>(SubtaskClass.class);
        ParentView p = new ParentView("A-1", "Pai A-1", "Refinado", carry, null, subtasks, sums,
                false, false, false, Map.of(), false);
        lenient().when(backlogService.view(eq("SQ1"), any(), any(), eq(false))).thenReturn(
                new PlannerBacklogService.BacklogView("2026-10-20T10:00:00Z", "S3", List.of(p), List.of()));
    }

    @BeforeEach
    void setUp() {
        service = new SprintPlanDistributionService(planRepository, itemRepository, assignmentRepository,
                holidayRepository, memberRepository, backlogService, capacityService, ws);
        plan = SprintPlan.builder().id("P1").squadId("SQ1").jiraSprintId("S3").sprintName("Sprint 3")
                .startDate(d(2)).endDate(d(13)).status(SprintPlan.STATUS_DISTRIBUICAO)
                .reserveMode(SprintPlan.RESERVE_HORAS).reservePct(h("12")).version(1L).build();
        item = SprintPlanItem.builder().id("I1").planId("P1").parentKey("A-1").sortOrder(0).carryOver(false)
                .addedBy("u").addedAt(Instant.now()).build();
        subtasks = new ArrayList<>(List.of(
                sub("A-1-1", SubtaskClass.DEV, false, "8", "8"),      // Codificação
                sub("A-1-2", SubtaskClass.DEV, true, "1", "1"),       // Code Review DEV
                sub("A-1-3", SubtaskClass.QA, false, "4", "4"),       // Execução de TI
                sub("A-1-4", SubtaskClass.QA, true, "1", "1"),        // Code Review QA
                sub("A-1-5", SubtaskClass.FORA_DA_CONTA, false, "2", "2"),
                sub("A-1-6", SubtaskClass.SEM_CLASSIFICACAO, false, "3", "3")));
        people.clear();
        people.add(person("acc-ana", "Ana", "DEV", 60));
        people.add(person("acc-bruno", "Bruno", "DEV", 10));
        people.add(person("acc-diego", "Diego", "QA", 60));

        lenient().when(planRepository.findById("P1")).thenReturn(Optional.of(plan));
        lenient().when(planRepository.findByIdForUpdate("P1")).thenReturn(Optional.of(plan));
        lenient().when(itemRepository.findByPlanIdOrderBySortOrderAsc("P1")).thenReturn(List.of(item));
        lenient().when(holidayRepository.findByPlanIdOrderByDayAsc("P1")).thenAnswer(i -> new ArrayList<>(holidays));
        lenient().when(capacityService.compute(any())).thenAnswer(i -> new CapacityView(Map.of("DEV", 70.0, "QA", 60.0),
                new ArrayList<>(people), 10));
        lenient().when(assignmentRepository.findByPlanId("P1")).thenAnswer(i -> new ArrayList<>(store.values()));
        lenient().when(assignmentRepository.findByPlanIdAndSubtaskKey(eq("P1"), any()))
                .thenAnswer(i -> Optional.ofNullable(store.get((String) i.getArgument(1))));
        lenient().when(assignmentRepository.saveAndFlush(any())).thenAnswer(i -> {
            SprintPlanAssignment a = i.getArgument(0);
            store.put(a.getSubtaskKey(), a);
            return a;
        });
        lenient().when(assignmentRepository.saveAllAndFlush(anyList())).thenAnswer(i -> {
            List<SprintPlanAssignment> l = i.getArgument(0);
            l.forEach(a -> store.put(a.getSubtaskKey(), a));
            return l;
        });
        lenient().doAnswer(i -> {
            store.remove(((SprintPlanAssignment) i.getArgument(0)).getSubtaskKey());
            return null;
        }).when(assignmentRepository).delete(any());
        mapUser("u-ana", "acc-ana");
        mapUser("u-bruno", "acc-bruno");
        mapUser("u-diego", "acc-diego");
        lenient().when(memberRepository.findByClaimedByUid("u-lead")).thenReturn(List.of());
        backlog();
    }

    void mapUser(String uid, String acc) {
        lenient().when(memberRepository.findByClaimedByUid(uid)).thenReturn(List.of(
                SquadMember.builder().dbId("SQ1_" + acc).squadId("SQ1").jiraAccountId(acc).claimedByUid(uid).build()));
    }

    SprintPlanAssignment row(String key, String acc, Integer s, Integer e) {
        SprintPlanAssignment a = SprintPlanAssignment.builder().id("R-" + key).planId("P1").itemId("I1").subtaskKey(key)
                .jiraAccountId(acc).assignmentClass("DEV").startDate(s == null ? null : d(s)).endDate(e == null ? null : d(e))
                .version(0L).updatedAt(Instant.now()).build();
        store.put(key, a);
        return a;
    }

    static DistSubtask sub(DistributionView v, String key) {
        return v.tasks().get(0).subtasks().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
    }

    static DistPerson pers(DistributionView v, String acc) {
        return v.people().stream().filter(p -> p.accountId().equals(acc)).findFirst().orElseThrow();
    }

    static ResponseStatusException status(Runnable r, HttpStatus expected) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, r::run);
        assertEquals(expected, ex.getStatusCode());
        return ex;
    }

    DistributionView claim(Caller c, String key, String acc) {
        return service.claim("SQ1", "P1", new ClaimRequest(key, acc), c);
    }

    DistributionView dates(Caller c, String key, String s, String e) {
        return service.setDates("SQ1", "P1", key, new DatesRequest(s, e), c);
    }

    // ---------------- claim ----------------

    @Test
    @DisplayName("DEV assume a própria subtarefa: vira dono, entra no saldo e o plano recebe REFRESH_PLAN")
    void claimOwn() {
        DistributionView v = claim(ANA, "A-1-1", null);
        DistSubtask s = sub(v, "A-1-1");
        assertEquals("acc-ana", s.assigneeId());
        assertEquals("Ana", s.assigneeName());
        assertEquals("DEV", s.cls());
        assertFalse(s.canClaim());
        assertTrue(s.canEditDates());
        assertEquals(8.0, pers(v, "acc-ana").allocatedHours());
        assertEquals(52.0, pers(v, "acc-ana").freeHours());
        assertEquals("acc-ana", v.myAccountId());
        verify(ws).broadcastRefresh("P1");
        assertEquals("DEV", store.get("A-1-1").getAssignmentClass());
        assertEquals(h("8"), store.get("A-1-1").getHours());
    }

    @Test
    @DisplayName("membro não atribui a outra pessoa (403)")
    void memberCannotAssignToOther() {
        status(() -> claim(ANA, "A-1-1", "acc-bruno"), HttpStatus.FORBIDDEN);
        assertTrue(store.isEmpty());
        verify(ws, never()).broadcastRefresh(any());
    }

    @Test
    @DisplayName("liderança atribui a outra pessoa")
    void leadershipAssignsToOther() {
        DistributionView v = claim(LEAD, "A-1-1", "acc-bruno");
        assertEquals("acc-bruno", sub(v, "A-1-1").assigneeId());
        assertTrue(v.canEditPlan());
    }

    @Test
    @DisplayName("subtarefa que já tem dono dá 409")
    void alreadyOwned409() {
        claim(ANA, "A-1-1", null);
        status(() -> claim(BRUNO, "A-1-1", null), HttpStatus.CONFLICT);
        status(() -> claim(LEAD, "A-1-1", "acc-bruno"), HttpStatus.CONFLICT);
        assertEquals("acc-ana", store.get("A-1-1").getJiraAccountId());
    }

    @Test
    @DisplayName("classe inválida: QA não pega DEV, DEV não pega QA, Sem classificação e Fora da conta não distribuem (422)")
    void invalidClass() {
        status(() -> claim(DIEGO, "A-1-1", null), HttpStatus.UNPROCESSABLE_ENTITY);
        status(() -> claim(ANA, "A-1-3", null), HttpStatus.UNPROCESSABLE_ENTITY);
        status(() -> claim(LEAD, "A-1-3", "acc-ana"), HttpStatus.UNPROCESSABLE_ENTITY);
        status(() -> claim(LEAD, "A-1-5", "acc-ana"), HttpStatus.UNPROCESSABLE_ENTITY);
        status(() -> claim(LEAD, "A-1-6", "acc-ana"), HttpStatus.UNPROCESSABLE_ENTITY);
        assertTrue(store.isEmpty());
    }

    @Test
    @DisplayName("pessoa fora da squad ou liderança sem conta na squad e sem accountId: 422")
    void notInSquad() {
        status(() -> claim(LEAD, "A-1-1", "acc-ninguem"), HttpStatus.UNPROCESSABLE_ENTITY);
        status(() -> claim(LEAD, "A-1-1", null), HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("etapa diferente de Distribuição: claim, liberar e datas dão 422")
    void wrongStage422() {
        plan.setStatus(SprintPlan.STATUS_ESCOPO);
        status(() -> claim(ANA, "A-1-1", null), HttpStatus.UNPROCESSABLE_ENTITY);
        status(() -> service.release("SQ1", "P1", "A-1-1", ANA), HttpStatus.UNPROCESSABLE_ENTITY);
        status(() -> dates(ANA, "A-1-1", "2026-11-02", "2026-11-03"), HttpStatus.UNPROCESSABLE_ENTITY);
        plan.setStatus(SprintPlan.STATUS_REVISAO);
        status(() -> claim(ANA, "A-1-1", null), HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("leitura funciona fora da Distribuição, mas ninguém pode assumir")
    void viewInReviewIsReadOnly() {
        plan.setStatus(SprintPlan.STATUS_REVISAO);
        DistributionView v = service.view("SQ1", "P1", ANA);
        assertFalse(sub(v, "A-1-1").canClaim());
    }

    @Test
    @DisplayName("subtarefa fora do plano: 404; plano de outra squad: 404")
    void notFound() {
        status(() -> claim(ANA, "X-9", null), HttpStatus.NOT_FOUND);
        status(() -> service.view("OUTRA", "P1", ANA), HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("violação da restrição única ao salvar vira 409 e não publica")
    void uniqueViolation409() {
        doThrow(new DataIntegrityViolationException("uq")).when(assignmentRepository).saveAndFlush(any());
        status(() -> claim(ANA, "A-1-1", null), HttpStatus.CONFLICT);
        verify(ws, never()).broadcastRefresh(any());
    }

    @Test
    @DisplayName("liberar: o dono e a liderança podem; outro membro não (403); a linha e as datas somem")
    void release() {
        claim(ANA, "A-1-1", null);
        status(() -> service.release("SQ1", "P1", "A-1-1", BRUNO), HttpStatus.FORBIDDEN);
        DistributionView v = service.release("SQ1", "P1", "A-1-1", ANA);
        assertNull(sub(v, "A-1-1").assigneeId());
        assertTrue(sub(v, "A-1-1").canClaim());
        assertTrue(store.isEmpty());
        claim(BRUNO, "A-1-1", null);
        service.release("SQ1", "P1", "A-1-1", LEAD);
        assertTrue(store.isEmpty());
        verify(ws, times(4)).broadcastRefresh("P1");
    }

    @Test
    @DisplayName("concorrência: dois DEVs assumem a mesma subtarefa ao mesmo tempo; um fica com ela e o outro leva 409")
    void concurrentClaim() throws Exception {
        CountDownLatch bothRead = new CountDownLatch(2);
        AtomicInteger inserts = new AtomicInteger();
        doAnswer(i -> {
            Optional<SprintPlanAssignment> cur = Optional.ofNullable(store.get((String) i.getArgument(1)));
            if (cur.isEmpty()) {
                bothRead.countDown();
                bothRead.await(2, TimeUnit.SECONDS);
            }
            return cur;
        }).when(assignmentRepository).findByPlanIdAndSubtaskKey(eq("P1"), any());
        doAnswer(i -> {
            SprintPlanAssignment a = i.getArgument(0);
            if (store.putIfAbsent(a.getSubtaskKey(), a) != null) throw new DataIntegrityViolationException("uq_plan_subtask");
            inserts.incrementAndGet();
            return a;
        }).when(assignmentRepository).saveAndFlush(any());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> ana = () -> codeOf(() -> claim(ANA, "A-1-1", null));
            Callable<Integer> bruno = () -> codeOf(() -> claim(BRUNO, "A-1-1", null));
            List<Future<Integer>> f = pool.invokeAll(List.of(ana, bruno));
            List<Integer> codes = new ArrayList<>(List.of(f.get(0).get(), f.get(1).get()));
            Collections.sort(codes);
            assertEquals(List.of(200, 409), codes);
            assertEquals(1, inserts.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private static int codeOf(Runnable r) {
        try {
            r.run();
            return 200;
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        }
    }

    // ---------------- datas e cadeia ----------------

    @Test
    @DisplayName("só o dono (ou liderança) marca datas: outro membro 403; sem dono, 422 para a liderança")
    void datesPermissions() {
        claim(ANA, "A-1-1", null);
        status(() -> dates(BRUNO, "A-1-1", "2026-11-02", "2026-11-03"), HttpStatus.FORBIDDEN);
        dates(ANA, "A-1-1", "2026-11-02", "2026-11-03");
        dates(LEAD, "A-1-1", "2026-11-02", "2026-11-04");
        assertEquals(d(4), store.get("A-1-1").getEndDate());
        status(() -> dates(LEAD, "A-1-2", "2026-11-02", "2026-11-03"), HttpStatus.UNPROCESSABLE_ENTITY);
        status(() -> dates(BRUNO, "A-1-2", "2026-11-02", "2026-11-03"), HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("datas inválidas: início depois do fim, fora da sprint, formato ruim, subtarefa fora do plano")
    void datesValidation() {
        claim(ANA, "A-1-1", null);
        status(() -> dates(ANA, "A-1-1", "2026-11-05", "2026-11-04"), HttpStatus.UNPROCESSABLE_ENTITY);
        status(() -> dates(ANA, "A-1-1", "2026-11-01", "2026-11-04"), HttpStatus.UNPROCESSABLE_ENTITY);
        status(() -> dates(ANA, "A-1-1", "2026-11-10", "2026-11-14"), HttpStatus.UNPROCESSABLE_ENTITY);
        status(() -> dates(ANA, "A-1-1", "amanha", "2026-11-04"), HttpStatus.UNPROCESSABLE_ENTITY);
        status(() -> dates(ANA, "X-9", "2026-11-02", "2026-11-04"), HttpStatus.NOT_FOUND);
        assertNull(store.get("A-1-1").getStartDate());
    }

    @Test
    @DisplayName("aceita data-hora ISO como o cronograma refeito e grava só a data")
    void acceptsDateTime() {
        claim(ANA, "A-1-1", null);
        DistributionView v = dates(ANA, "A-1-1", "2026-11-03T09:00:00-03:00", "2026-11-04T18:00:00-03:00");
        assertEquals(d(3), sub(v, "A-1-1").startDate());
        assertEquals(d(4), sub(v, "A-1-1").endDate());
    }

    @Test
    @DisplayName("Code Review não começa antes do fim da Codificação; QA não começa antes do fim do Code Review (422 com minStartDate)")
    void chainEnforced() {
        claim(ANA, "A-1-1", null);
        claim(ANA, "A-1-2", null);
        claim(DIEGO, "A-1-3", null);
        dates(ANA, "A-1-1", "2026-11-02", "2026-11-04");
        DistributionRuleException crEx = assertThrows(DistributionRuleException.class,
                () -> dates(ANA, "A-1-2", "2026-11-03", "2026-11-05"));
        assertEquals(d(4), crEx.minStartDate());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, crEx.getStatusCode());
        dates(ANA, "A-1-2", "2026-11-04", "2026-11-05");
        DistributionRuleException qaEx = assertThrows(DistributionRuleException.class,
                () -> dates(DIEGO, "A-1-3", "2026-11-04", "2026-11-06"));
        assertEquals(d(5), qaEx.minStartDate());
        DistributionView v = dates(DIEGO, "A-1-3", "2026-11-05", "2026-11-06");
        assertEquals("A-1-2", sub(v, "A-1-3").startsAfterKey());
        assertEquals(d(5), sub(v, "A-1-3").minStartDate());
        assertEquals("A-1-1", sub(v, "A-1-2").startsAfterKey());
        assertNull(sub(v, "A-1-1").startsAfterKey());
        assertNull(sub(v, "A-1-1").minStartDate());
    }

    @Test
    @DisplayName("cascata para a frente: DEV adia o fim, CR e QA acompanham pelo delta com a mesma duração")
    void cascadeForward() {
        row("A-1-1", "acc-ana", 2, 4);
        row("A-1-2", "acc-ana", 4, 5);
        row("A-1-3", "acc-diego", 5, 6);
        DistributionView v = dates(ANA, "A-1-1", "2026-11-02", "2026-11-06");
        assertEquals(d(6), sub(v, "A-1-1").endDate());
        assertEquals(d(6), sub(v, "A-1-2").startDate());
        assertEquals(d(9), sub(v, "A-1-2").endDate());
        assertEquals(d(9), sub(v, "A-1-3").startDate());
        assertEquals(d(10), sub(v, "A-1-3").endDate());
        verify(ws).broadcastRefresh("P1");
    }

    @Test
    @DisplayName("cascata para trás: DEV antecipa o fim e o QA volta junto")
    void cascadeBackward() {
        row("A-1-1", "acc-ana", 2, 5);
        row("A-1-2", "acc-ana", 5, 6);
        row("A-1-3", "acc-diego", 6, 9);
        DistributionView v = dates(ANA, "A-1-1", "2026-11-02", "2026-11-03");
        assertEquals(d(3), sub(v, "A-1-2").startDate());
        assertEquals(d(4), sub(v, "A-1-2").endDate());
        assertEquals(d(4), sub(v, "A-1-3").startDate());
        assertEquals(d(5), sub(v, "A-1-3").endDate());
    }

    @Test
    @DisplayName("cascata que empurraria alguém para depois do fim da sprint é recusada (422)")
    void cascadeOutsideSprint() {
        row("A-1-1", "acc-ana", 2, 4);
        row("A-1-2", "acc-ana", 4, 5);
        row("A-1-3", "acc-diego", 12, 13);
        status(() -> dates(ANA, "A-1-1", "2026-11-02", "2026-11-09"), HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ---------------- saldo, termômetro e alertas ----------------

    @Test
    @DisplayName("saldo por pessoa: capacidade menos o atribuído; termômetro verde, amarelo, coberto pela reserva e acima")
    void balanceAndThermometer() {
        subtasks.add(sub("A-1-7", SubtaskClass.DEV, false, "2", "2"));
        subtasks.add(sub("A-1-8", SubtaskClass.DEV, false, "3", "3"));
        backlog();
        DistributionView v = claim(BRUNO, "A-1-1", null); // 8 de 10 h
        DistPerson p = pers(v, "acc-bruno");
        assertEquals(8.0, p.allocatedHours());
        assertEquals(2.0, p.freeHours());
        assertEquals(80.0, p.pct());
        assertEquals("VERDE", p.zone());
        assertEquals("DENTRO_DA_CAPACIDADE", p.state());

        p = pers(claim(BRUNO, "A-1-2", null), "acc-bruno"); // 9 h = 90%
        assertEquals(90.0, p.pct());
        assertEquals("AMARELO", p.zone());

        v = claim(BRUNO, "A-1-7", null); // 11 h = 110%, reserva 12%
        p = pers(v, "acc-bruno");
        assertEquals(110.0, p.pct());
        assertEquals("VERMELHO", p.zone());
        assertEquals("COBERTO_PELA_RESERVA", p.state());
        assertEquals(-1.0, p.freeHours());
        assertTrue(v.warnings().stream().anyMatch(w -> w.code().equals("PERSON_OVER_CAPACITY")
                && "acc-bruno".equals(w.accountId()) && w.message().contains("coberto pela reserva")));

        v = claim(BRUNO, "A-1-8", null); // 14 h = 140%
        p = pers(v, "acc-bruno");
        assertEquals("ACIMA_DA_RESERVA", p.state());
        assertEquals(0.0, pers(v, "acc-ana").allocatedHours());
        assertEquals("VERDE", pers(v, "acc-ana").zone());
    }

    @Test
    @DisplayName("sobra (carry-over) pesa o restante, não o estimado")
    void carryOverUsesRemaining() {
        carry = true;
        item.setCarryOver(true);
        subtasks.set(0, sub("A-1-1", SubtaskClass.DEV, false, "8", "3"));
        backlog();
        DistributionView v = claim(ANA, "A-1-1", null);
        assertEquals(h("3"), sub(v, "A-1-1").countedH());
        assertEquals(3.0, pers(v, "acc-ana").allocatedHours());
    }

    @Test
    @DisplayName("people só lista DEV/QA incluídos; Fora da conta e Sem classificação saem como FORA/SEM sem claim")
    void peopleAndNonDistributable() {
        people.add(person("acc-sem-papel", "Sem Papel", "", 10));
        DistributionView v = service.view("SQ1", "P1", LEAD);
        assertEquals(3, v.people().size());
        assertEquals("FORA", sub(v, "A-1-5").cls());
        assertEquals("SEM", sub(v, "A-1-6").cls());
        assertFalse(sub(v, "A-1-5").canClaim());
        assertFalse(sub(v, "A-1-6").canClaim());
        assertTrue(sub(v, "A-1-1").canClaim());
        DistributionView asQa = service.view("SQ1", "P1", DIEGO);
        assertFalse(sub(asQa, "A-1-1").canClaim());
        assertTrue(sub(asQa, "A-1-3").canClaim());
    }

    @Test
    @DisplayName("alertas: sem dono, sem datas, QA antes do DEV, fora da sprint e feriado; nunca bloqueiam a leitura")
    void warnings() {
        holidays.add(SprintPlanHoliday.builder().planId("P1").day(d(9)).label("Feriado").build());
        row("A-1-1", "acc-ana", 2, 5);
        row("A-1-2", "acc-ana", null, null);
        row("A-1-3", "acc-diego", 4, 9);     // começa antes do fim da Codificação e termina em feriado
        row("A-1-4", "acc-diego", 16, 17);   // fora da sprint
        DistributionView v = service.view("SQ1", "P1", LEAD);
        Set<String> codes = new HashSet<>();
        v.warnings().forEach(w -> codes.add(w.code()));
        assertEquals(Set.of("SUBTASK_NO_DATES", "QA_BEFORE_DEV", "TASK_ON_HOLIDAY", "DATES_OUTSIDE_SPRINT"), codes);
        assertTrue(v.warnings().stream().anyMatch(w -> w.code().equals("QA_BEFORE_DEV") && "A-1-3".equals(w.subtaskKey())
                && "acc-diego".equals(w.accountId())));

        store.clear();
        DistributionView empty = service.view("SQ1", "P1", LEAD);
        assertEquals(4, empty.warnings().stream().filter(w -> w.code().equals("SUBTASK_UNASSIGNED")).count());
        assertNotNull(empty.sprint());
        assertEquals(1, empty.sprint().holidays().size());
    }

    @Test
    @DisplayName("warningsFor (aviso do avanço para a Revisão) devolve os alertas sem bloquear")
    void warningsForAdvance() {
        List<DistWarning> w = service.warningsFor("SQ1", "P1");
        assertEquals(4, w.stream().filter(x -> x.code().equals("SUBTASK_UNASSIGNED")).count());
    }
}
