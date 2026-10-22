package com.agilespace.backend.service.sprintplan;

import com.agilespace.backend.domain.SprintPlan;
import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.domain.SquadIssueSnapshot;
import com.agilespace.backend.dto.SprintPlanDistributionDtos.*;
import com.agilespace.backend.dto.SprintPlanDtos.ItemView;
import com.agilespace.backend.dto.SprintPlanDtos.ScopeTotals;
import com.agilespace.backend.dto.SprintPlanReviewDtos.*;
import com.agilespace.backend.repository.SprintPlanRepository;
import com.agilespace.backend.repository.SquadIssueSnapshotRepository;
import com.agilespace.backend.service.JiraDateFieldResolver;
import com.agilespace.backend.service.JiraDateFieldResolver.FieldRef;
import com.agilespace.backend.service.SquadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SprintPlanReviewService - pendências, prévia, contadores e canSend")
class SprintPlanReviewServiceTest {

    @Mock SprintPlanRepository planRepository;
    @Mock SprintPlanDistributionService distributionService;
    @Mock SprintPlanScopeService scopeService;
    @Mock SquadIssueSnapshotRepository snapshotRepository;
    @Mock SquadService squadService;
    @Mock JiraDateFieldResolver resolver;

    SprintPlanReviewService service;
    SprintPlan plan;
    Squad squad;
    List<DistWarning> warnings = new ArrayList<>();
    List<DistTask> tasks = new ArrayList<>();
    List<ItemView> items = new ArrayList<>();
    int unclassified = 0;
    List<SquadIssueSnapshot> snapshots = new ArrayList<>();

    static LocalDate d(int day) {
        return LocalDate.of(2026, 11, day);
    }

    static BigDecimal h(String v) {
        return new BigDecimal(v);
    }

    static DistSubtask sub(String key, String cls, String acc, Integer s, Integer e) {
        return new DistSubtask(key, "Sub " + key, "t", "Tipo " + key, cls, false, h("8"), h("8"), h("8"), acc,
                acc == null ? null : "Nome " + acc, s == null ? null : d(s), e == null ? null : d(e), null, null,
                false, false, "Aberto");
    }

    static ItemView item(String id, String key, boolean divergent, boolean noEstimate) {
        return new ItemView(id, key, "Pai " + key, null, null, 0, false, h("8"), h("4"), h("8"), h("4"),
                h("8"), h("8"), h("4"), h("4"), false, false, divergent, noEstimate, true, 0, BigDecimal.ZERO,
                List.of(), BigDecimal.ZERO, BigDecimal.ZERO, false);
    }

    @BeforeEach
    void setUp() {
        service = new SprintPlanReviewService(planRepository, distributionService, scopeService, snapshotRepository,
                squadService, resolver);
        plan = SprintPlan.builder().id("P1").squadId("SQ1").jiraSprintId("S3").sprintName("Sprint 3")
                .startDate(d(2)).endDate(d(13)).status(SprintPlan.STATUS_REVISAO).goal("Entregar X")
                .reserveMode(SprintPlan.RESERVE_HORAS).version(1L).build();
        squad = Squad.builder().jiraDomain("totvs.atlassian.net").build();
        tasks.add(new DistTask("I1", "A-1", "Pai A-1", h("8"), h("4"), List.of(
                sub("A-1-1", "DEV", "acc-ana", 2, 4), sub("A-1-2", "QA", "acc-diego", 5, 6))));
        items.add(item("I1", "A-1", false, false));
        lenient().when(planRepository.findById("P1")).thenReturn(Optional.of(plan));
        lenient().when(squadService.getSquad("SQ1")).thenAnswer(i -> Optional.of(squad));
        lenient().when(distributionService.buildView(any(), any())).thenAnswer(i -> new DistributionView("P1",
                plan.getStatus(), "sync", false, null, new SprintInfo(d(2), d(13), List.of()), tasks,
                List.of(new DistPerson("acc-ana", "Ana", "DEV", 60, 8, 52, 13.3, "VERDE", "DENTRO_DA_CAPACIDADE"),
                        new DistPerson("acc-diego", "Diego", "QA", 60, 8, 52, 13.3, "VERDE", "DENTRO_DA_CAPACIDADE")),
                warnings));
        lenient().when(scopeService.compute(any(SprintPlan.class))).thenAnswer(i -> new SprintPlanScopeService.Computed(items,
                new ScopeTotals(items.size(), Map.of(), 0, 0, 0, 0, 0, unclassified, 0, List.of())));
        lenient().when(snapshotRepository.findBySquadIdAndJiraKeyIn(eq("SQ1"), any())).thenAnswer(i -> snapshots);
    }

    ReviewView review() {
        return service.review("SQ1", "P1", true);
    }

    Pending find(ReviewView v, String code) {
        return v.pendings().stream().filter(p -> p.code().equals(code)).findFirst().orElseThrow();
    }

    static SquadIssueSnapshot snap(String key, String assignee, String sprint, String start, String end, boolean inferred) {
        return SquadIssueSnapshot.builder().jiraKey(key).assigneeId(assignee).assigneeName(assignee == null ? null : "Nome " + assignee)
                .sprintName(sprint).targetStart(start).targetEnd(end).datesAreInferred(inferred).build();
    }

    // ---------------- Pendências ----------------

    @Test
    @DisplayName("tudo certo: sem pendências, mas canSend=false enquanto a E6 não existe")
    void allGood() {
        ReviewView v = review();
        assertTrue(v.pendings().isEmpty());
        assertEquals(0, v.blockingCount());
        assertEquals(0, v.warningCount());
        assertFalse(v.canSend());
        assertFalse(v.writeRulesDefined());
        assertEquals("Regras de escrita no Jira em definição", v.jiraPreview().rulesNote());
    }

    @Test
    @DisplayName("pendências que impedem o envio vêm como BLOCKING")
    void blockingCodes() {
        tasks.clear();
        warnings.add(new DistWarning("SUBTASK_UNASSIGNED", "m", "A-1-1", null));
        warnings.add(new DistWarning("SUBTASK_NO_DATES", "m", "A-1-2", "acc-ana"));
        warnings.add(new DistWarning("QA_BEFORE_DEV", "m", "A-1-3", "acc-diego"));
        warnings.add(new DistWarning("CODE_REVIEW_BEFORE_CODING", "m", "A-1-4", "acc-ana"));
        warnings.add(new DistWarning("DATES_OUTSIDE_SPRINT", "m", "A-1-5", "acc-ana"));
        ReviewView v = review();
        // escopo vazio (tasks vazio) + as cinco da distribuição
        assertEquals(6, v.blockingCount());
        assertEquals(0, v.warningCount());
        assertEquals("BLOCKING", find(v, "SCOPE_EMPTY").severity());
        for (String code : List.of("SUBTASK_UNASSIGNED", "SUBTASK_NO_DATES", "QA_BEFORE_DEV",
                "CODE_REVIEW_BEFORE_CODING", "DATES_OUTSIDE_SPRINT")) {
            assertEquals("BLOCKING", find(v, code).severity(), code);
        }
        assertFalse(v.canSend());
    }

    @Test
    @DisplayName("pendência de subtarefa traz o itemId e a pessoa")
    void pendingCarriesItemAndPerson() {
        warnings.add(new DistWarning("SUBTASK_NO_DATES", "m", "A-1-2", "acc-diego"));
        Pending p = find(review(), "SUBTASK_NO_DATES");
        assertEquals("I1", p.itemId());
        assertEquals("acc-diego", p.accountId());
        assertEquals("A-1-2", p.subtaskKey());
    }

    @Test
    @DisplayName("avisos: capacidade, feriado, divergência, sem estimativa, tipos sem classificação, meta vazia")
    void warningCodes() {
        warnings.add(new DistWarning("PERSON_OVER_CAPACITY", "m", null, "acc-ana"));
        warnings.add(new DistWarning("TASK_ON_HOLIDAY", "m", "A-1-1", "acc-ana"));
        items.set(0, item("I1", "A-1", true, true));
        unclassified = 2;
        plan.setGoal("  ");
        ReviewView v = review();
        assertEquals(0, v.blockingCount());
        assertEquals(6, v.warningCount());
        for (String code : List.of("PERSON_OVER_CAPACITY", "TASK_ON_HOLIDAY", "TITLE_DIVERGENCE", "NO_ESTIMATE",
                "UNCLASSIFIED_TYPES", "GOAL_EMPTY")) {
            assertEquals("WARNING", find(v, code).severity(), code);
        }
        assertFalse(v.canSend());
    }

    @Test
    @DisplayName("contadores separam bloqueios e avisos; bloqueios vêm primeiro")
    void countersAndOrder() {
        warnings.add(new DistWarning("PERSON_OVER_CAPACITY", "m", null, "acc-ana"));
        warnings.add(new DistWarning("SUBTASK_NO_DATES", "m", "A-1-2", "acc-diego"));
        warnings.add(new DistWarning("SUBTASK_UNASSIGNED", "m", "A-1-3", null));
        ReviewView v = review();
        assertEquals(2, v.blockingCount());
        assertEquals(1, v.warningCount());
        assertEquals("BLOCKING", v.pendings().get(0).severity());
        assertEquals("WARNING", v.pendings().get(2).severity());
    }

    // ---------------- Resumo ----------------

    @Test
    @DisplayName("resumo por pessoa conta subtarefas e subtarefas com datas; tarefas só trazem DEV/QA")
    void summary() {
        tasks.set(0, new DistTask("I1", "A-1", "Pai A-1", h("8"), h("4"), List.of(
                sub("A-1-1", "DEV", "acc-ana", 2, 4), sub("A-1-2", "DEV", "acc-ana", null, null),
                sub("A-1-3", "QA", "acc-diego", 5, 6), sub("A-1-4", "FORA", null, null, null))));
        ReviewView v = review();
        ReviewPerson ana = v.people().stream().filter(p -> p.accountId().equals("acc-ana")).findFirst().orElseThrow();
        assertEquals(2, ana.subtaskCount());
        assertEquals(1, ana.datedSubtaskCount());
        assertEquals(3, v.tasks().get(0).subtasks().size());
        assertEquals("Sprint 3", v.sprint().name());
        assertEquals("Entregar X", v.goal());
        assertFalse(v.goalSavedInJira());
        assertTrue(v.canEditPlan());
    }

    // ---------------- Prévia ----------------

    @Test
    @DisplayName("sem nada no Jira: tudo SET; campos 'a definir' quando o resolvedor não conhece")
    void previewAllSetFieldTbd() {
        JiraPreview p = review().jiraPreview();
        // pai: sprint; cada subtarefa: assignee, sprint, início, fim
        assertEquals(1 + 2 * 4, p.changes().size());
        assertTrue(p.changes().stream().allMatch(c -> "SET".equals(c.kind())));
        PreviewChange start = p.changes().stream().filter(c -> c.field().equals("plannedStart")).findFirst().orElseThrow();
        assertEquals("campo a definir", start.jiraFieldName());
        assertEquals("2026-11-02", start.to());
        assertEquals(new PreviewCounts(9, 0, 0, 3), p.counts());
        assertEquals("SET", p.sprintGoal().kind());
        assertEquals("Entregar X", p.sprintGoal().to());
    }

    @Test
    @DisplayName("nome real dos campos vem do resolvedor (cache) quando conhecido")
    void previewFieldNamesFromResolver() {
        squad.setJiraStartField("customfield_1");
        when(resolver.peek("totvs.atlassian.net")).thenReturn(new JiraDateFieldResolver.Resolution(
                List.of(new FieldRef("customfield_1", "Data e Hora de Início")),
                List.of(new FieldRef("customfield_2", "Data e Hora de Fim")),
                Map.of("customfield_1", "Data e Hora de Início", "customfield_2", "Data e Hora de Fim")));
        JiraPreview p = review().jiraPreview();
        assertEquals("Data e Hora de Início", p.changes().stream().filter(c -> c.field().equals("plannedStart"))
                .findFirst().orElseThrow().jiraFieldName());
        // o fim não foi configurado, mas há um único candidato
        assertEquals("Data e Hora de Fim", p.changes().stream().filter(c -> c.field().equals("plannedEnd"))
                .findFirst().orElseThrow().jiraFieldName());
    }

    @Test
    @DisplayName("de -> para: UNCHANGED, CHANGE e SET conforme o snapshot")
    void previewKinds() {
        snapshots.add(snap("A-1", null, "Sprint 3", null, null, false));
        snapshots.add(snap("A-1-1", "acc-ana", "Sprint 2", "2026-11-02T09:00:00", "2026-11-09", false));
        snapshots.add(snap("A-1-2", "acc-outro", null, "2026-11-01", "2026-11-01", true));
        JiraPreview p = review().jiraPreview();
        java.util.function.BiFunction<String, String, PreviewChange> get = (k, f) -> p.changes().stream()
                .filter(c -> c.issueKey().equals(k) && c.field().equals(f)).findFirst().orElseThrow();
        assertEquals("UNCHANGED", get.apply("A-1", "sprint").kind());
        assertEquals("UNCHANGED", get.apply("A-1-1", "assignee").kind());
        assertEquals("CHANGE", get.apply("A-1-1", "sprint").kind());
        assertEquals("Sprint 2", get.apply("A-1-1", "sprint").from());
        assertEquals("UNCHANGED", get.apply("A-1-1", "plannedStart").kind());
        assertEquals("2026-11-02", get.apply("A-1-1", "plannedStart").from());
        assertEquals("CHANGE", get.apply("A-1-1", "plannedEnd").kind());
        assertEquals("CHANGE", get.apply("A-1-2", "assignee").kind());
        assertEquals("Nome acc-outro", get.apply("A-1-2", "assignee").from());
        // data inferida pelo sync não é valor atual no Jira
        assertEquals("SET", get.apply("A-1-2", "plannedStart").kind());
        assertNull(get.apply("A-1-2", "plannedStart").from());
        PreviewCounts c = p.counts();
        assertEquals(p.changes().size(), c.set() + c.change() + c.unchanged());
        assertEquals(3, c.issues());
    }

    @Test
    @DisplayName("subtarefa sem dono não entra na prévia; sem datas não gera início/fim")
    void previewSkipsUnassignedAndUndated() {
        tasks.set(0, new DistTask("I1", "A-1", "Pai A-1", h("8"), h("4"), List.of(
                sub("A-1-1", "DEV", null, null, null), sub("A-1-2", "QA", "acc-diego", null, null))));
        JiraPreview p = review().jiraPreview();
        assertTrue(p.changes().stream().noneMatch(c -> c.issueKey().equals("A-1-1")));
        assertTrue(p.changes().stream().noneMatch(c -> c.field().startsWith("planned")));
    }

    @Test
    @DisplayName("meta vazia: prévia sem mudança de meta")
    void previewGoalEmpty() {
        plan.setGoal(null);
        GoalChange g = review().jiraPreview().sprintGoal();
        assertEquals("UNCHANGED", g.kind());
        assertNull(g.to());
    }

    @Test
    @DisplayName("plano enviado: goalSavedInJira e aviso de regra em definição para mudanças posteriores")
    void afterSend() {
        plan.setStatus(SprintPlan.STATUS_ENVIADO);
        ReviewView v = review();
        assertTrue(v.goalSavedInJira());
        assertTrue(v.jiraPreview().rulesNote().contains("regra em definição"));
        assertFalse(v.canSend());
    }

    @Test
    @DisplayName("link do Jira da squad; null sem domínio")
    void jiraUrl() {
        assertEquals("https://totvs.atlassian.net/secure/RapidBoard.jspa", review().jiraSprintUrl());
        squad.setJiraDomain(null);
        assertNull(review().jiraSprintUrl());
    }

    // ---------------- Acesso ----------------

    @Test
    @DisplayName("plano inexistente ou de outra squad: 404")
    void notFound() {
        when(planRepository.findById("PX")).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.review("SQ1", "PX", false)).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.review("OUTRA", "P1", false)).getStatusCode());
    }
}
