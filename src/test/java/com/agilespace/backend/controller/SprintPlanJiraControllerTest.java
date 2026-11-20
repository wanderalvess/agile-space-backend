package com.agilespace.backend.controller;

import com.agilespace.backend.config.ErrorMessageAdvice;
import com.agilespace.backend.domain.*;
import com.agilespace.backend.dto.SprintPlanDtos.PlanView;
import com.agilespace.backend.repository.*;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.JiraService;
import com.agilespace.backend.service.SquadAccessService;
import com.agilespace.backend.service.SquadService;
import com.agilespace.backend.service.sprintplan.SprintPlanCopyService;
import com.agilespace.backend.service.sprintplan.SprintPlanJiraService;
import com.agilespace.backend.service.sprintplan.SprintPlanService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("SprintPlanJiraController - copiar da sprint anterior e proxima sprint do Jira")
class SprintPlanJiraControllerTest {

    @Mock SquadAccessService access;
    @Mock SprintPlanRepository planRepository;
    @Mock SprintPlanPersonRepository personRepository;
    @Mock SprintPlanReserveTypeRepository reserveTypeRepository;
    @Mock SprintPlanService planService;
    @Mock SquadService squadService;
    @Mock JiraService jiraService;
    @Mock UserJiraConfigRepository userJiraConfigRepository;

    MockMvc mvc;
    SprintPlan target;
    SprintPlan prev;
    Squad squad;
    java.time.Clock clock = java.time.Clock.fixed(java.time.Instant.parse("2026-10-23T10:00:00Z"), java.time.ZoneOffset.UTC);

    static LocalDate d(int m, int day) { return LocalDate.of(2026, m, day); }

    @BeforeEach
    void setUp() {
        SprintPlanCopyService copy = new SprintPlanCopyService(planRepository, personRepository, reserveTypeRepository, planService);
        SprintPlanJiraService jira = new SprintPlanJiraService(squadService, jiraService, userJiraConfigRepository,
                planRepository, new ObjectMapper(), 10);
        mvc = MockMvcBuilders.standaloneSetup(new SprintPlanJiraController(copy, jira, access))
                .setControllerAdvice(new ErrorMessageAdvice()).build();
        target = SprintPlan.builder().id("P2").squadId("SQ1").sprintName("Sprint 2").startDate(d(11, 2)).endDate(d(11, 13))
                .status(SprintPlan.STATUS_RASCUNHO).reserveMode(SprintPlan.RESERVE_HORAS).version(1L).build();
        prev = SprintPlan.builder().id("P1").squadId("SQ1").sprintName("Sprint 1").startDate(d(10, 19)).endDate(d(10, 30))
                .status(SprintPlan.STATUS_REVISAO).reserveMode(SprintPlan.RESERVE_FOCO).reservePct(new BigDecimal("15"))
                .focusFactorPct(new BigDecimal("70")).version(4L).build();
        squad = Squad.builder().id("SQ1").jiraDomain("totvs.atlassian.net").rapidViewId("77").build();
        lenient().when(planRepository.findById("P2")).thenReturn(Optional.of(target));
        lenient().when(planRepository.findById("P1")).thenReturn(Optional.of(prev));
        lenient().when(planRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(planRepository.findBySquadIdOrderByStartDateDesc("SQ1")).thenReturn(List.of(target, prev));
        lenient().when(squadService.getSquad("SQ1")).thenReturn(Optional.of(squad));
        lenient().when(planService.view(eq("SQ1"), eq("P2"), eq(true), any())).thenReturn(new PlanView("P2", "SQ1", 2L,
                "RASCUNHO", null, null, null, null, null, List.of(), List.of(), List.of(), null, List.of(), null, null,
                false, null, null, null));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String user) {
        return b.requestAttr(JwtAuthenticationFilter.ATTR_USER_ID, user)
                .requestAttr(JwtAuthenticationFilter.ATTR_USER_ROLE, "USER").contentType(MediaType.APPLICATION_JSON);
    }

    private void leader() {
        when(access.isSquadLeadership("SQ1", "lead", "USER")).thenReturn(true);
    }

    // ---------------- copy-from-previous ----------------

    @Test
    @DisplayName("copia reserva, tipos, foco do time e pessoas da sprint anterior; nao toca ausencias/feriados/itens")
    void copiesOnlyWhatShould() throws Exception {
        leader();
        when(reserveTypeRepository.findByPlanIdOrderByLabelAsc("P1")).thenReturn(List.of(
                SprintPlanReserveType.builder().id("t1").planId("P1").label("Reuniao").hoursPerDay(new BigDecimal("1.5")).build()));
        when(personRepository.findByPlanId("P1")).thenReturn(List.of(
                SprintPlanPerson.builder().planId("P1").jiraAccountId("acc-a").included(false).focusFactorPct(new BigDecimal("60")).build(),
                SprintPlanPerson.builder().planId("P1").jiraAccountId("acc-b").included(true).build()));

        mvc.perform(as(post("/api/squads/SQ1/sprint-plans/P2/copy-from-previous"), "lead"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("P2"));

        assertEquals(SprintPlan.RESERVE_FOCO, target.getReserveMode());
        assertEquals(0, new BigDecimal("15").compareTo(target.getReservePct()));
        assertEquals(0, new BigDecimal("70").compareTo(target.getFocusFactorPct()));
        verify(reserveTypeRepository).deleteAllForPlan("P2");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SprintPlanReserveType>> types = ArgumentCaptor.forClass(List.class);
        verify(reserveTypeRepository).saveAllAndFlush(types.capture());
        assertEquals("Reuniao", types.getValue().get(0).getLabel());
        assertEquals("P2", types.getValue().get(0).getPlanId());
        assertNotEquals("t1", types.getValue().get(0).getId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SprintPlanPerson>> people = ArgumentCaptor.forClass(List.class);
        verify(personRepository).saveAllAndFlush(people.capture());
        assertEquals(2, people.getValue().size());
        assertEquals("P2", people.getValue().get(0).getPlanId());
        assertFalse(people.getValue().get(0).isIncluded());
        assertEquals(0, new BigDecimal("60").compareTo(people.getValue().get(0).getFocusFactorPct()));
    }

    @Test
    @DisplayName("com sourcePlanId usa esse plano; de outra squad ou inexistente: 404")
    void explicitSource() throws Exception {
        leader();
        mvc.perform(as(post("/api/squads/SQ1/sprint-plans/P2/copy-from-previous").content("{\"sourcePlanId\":\"P1\"}"), "lead"))
                .andExpect(status().isOk());
        verify(personRepository).findByPlanId("P1");

        SprintPlan other = SprintPlan.builder().id("PX").squadId("OUTRA").startDate(d(9, 1)).endDate(d(9, 10))
                .status("REVISAO").reserveMode("HORAS").version(1L).build();
        when(planRepository.findById("PX")).thenReturn(Optional.of(other));
        mvc.perform(as(post("/api/squads/SQ1/sprint-plans/P2/copy-from-previous").content("{\"sourcePlanId\":\"PX\"}"), "lead"))
                .andExpect(status().isNotFound());
        mvc.perform(as(post("/api/squads/SQ1/sprint-plans/P2/copy-from-previous").content("{\"sourcePlanId\":\"NAO\"}"), "lead"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("sem plano que termine antes do inicio: 404 'Nenhum planejamento anterior'")
    void noPrevious() throws Exception {
        leader();
        prev.setEndDate(d(11, 2)); // termina no mesmo dia do inicio: nao e anterior
        mvc.perform(as(post("/api/squads/SQ1/sprint-plans/P2/copy-from-previous"), "lead"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Nenhum planejamento anterior"));
        verify(personRepository, never()).saveAllAndFlush(anyList());
    }

    @Test
    @DisplayName("plano fora de RASCUNHO: 422; plano de outra squad: 404; membro: 403")
    void copyGuards() throws Exception {
        leader();
        target.setStatus(SprintPlan.STATUS_ESCOPO);
        mvc.perform(as(post("/api/squads/SQ1/sprint-plans/P2/copy-from-previous"), "lead")).andExpect(status().isUnprocessableEntity());
        target.setStatus(SprintPlan.STATUS_RASCUNHO);
        target.setSquadId("OUTRA");
        mvc.perform(as(post("/api/squads/SQ1/sprint-plans/P2/copy-from-previous"), "lead")).andExpect(status().isNotFound());
        when(access.isSquadLeadership("SQ1", "membro", "USER")).thenReturn(false);
        mvc.perform(as(post("/api/squads/SQ1/sprint-plans/P2/copy-from-previous"), "membro")).andExpect(status().isForbidden());
    }

    // ---------------- next-sprint ----------------

    private void jiraConfigured() {
        when(userJiraConfigRepository.findById("lead")).thenReturn(Optional.of(
                UserJiraConfig.builder().userId("lead").token("tok").domain("totvs.atlassian.net").build()));
    }

    private static final String SPRINTS = "{\"values\":["
            + "{\"id\":10,\"name\":\"Sprint ativa\",\"state\":\"active\",\"startDate\":\"2026-10-19T09:00:00.000Z\",\"endDate\":\"2026-10-30T18:00:00.000Z\"},"
            + "{\"id\":12,\"name\":\"Sprint 12\",\"state\":\"future\",\"startDate\":\"2026-11-16T09:00:00.000Z\",\"endDate\":\"2026-11-27T18:00:00.000Z\"},"
            + "{\"id\":11,\"name\":\"Sprint 11\",\"state\":\"future\",\"startDate\":\"2026-11-02T09:00:00.000Z\",\"endDate\":\"2026-11-13T18:00:00.000Z\",\"goal\":\"Entregar X\"}]}";

    @Test
    @DisplayName("encontrada: escolhe a futura mais proxima; datas yyyy-MM-dd; meta; state future")
    void nextFound() throws Exception {
        leader();
        jiraConfigured();
        when(jiraService.listBoardSprints("totvs.atlassian.net", "tok", "77")).thenReturn(ResponseEntity.ok(SPRINTS));
        mvc.perform(as(get("/api/squads/SQ1/sprint-plans/next-sprint"), "lead"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.jiraSprintId").value("11"))
                .andExpect(jsonPath("$.name").value("Sprint 11"))
                .andExpect(jsonPath("$.startDate").value("2026-11-02"))
                .andExpect(jsonPath("$.endDate").value("2026-11-13"))
                .andExpect(jsonPath("$.goal").value("Entregar X"))
                .andExpect(jsonPath("$.state").value("future"));
    }

    @Test
    @DisplayName("sem sprint futura: found=false e campos nulos")
    void nextNotFound() throws Exception {
        leader();
        jiraConfigured();
        when(jiraService.listBoardSprints(any(), any(), any())).thenReturn(ResponseEntity.ok(
                "{\"values\":[{\"id\":10,\"name\":\"A\",\"state\":\"active\"}]}"));
        mvc.perform(as(get("/api/squads/SQ1/sprint-plans/next-sprint"), "lead"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.jiraSprintId").doesNotExist())
                .andExpect(jsonPath("$.goal").doesNotExist());
    }

    @Test
    @DisplayName("sem token: 400; sem board: 400 (mensagens claras); membro: 403")
    void nextConfigErrors() throws Exception {
        leader();
        when(userJiraConfigRepository.findById("lead")).thenReturn(Optional.empty());
        mvc.perform(as(get("/api/squads/SQ1/sprint-plans/next-sprint"), "lead"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Token")));
        squad.setRapidViewId(null);
        mvc.perform(as(get("/api/squads/SQ1/sprint-plans/next-sprint"), "lead"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("board")));
        when(access.isSquadLeadership("SQ1", "membro", "USER")).thenReturn(false);
        mvc.perform(as(get("/api/squads/SQ1/sprint-plans/next-sprint"), "membro")).andExpect(status().isForbidden());
        verifyNoInteractions(jiraService);
    }

    @Test
    @DisplayName("Jira falha (erro HTTP): 502")
    void nextJiraFails() throws Exception {
        leader();
        jiraConfigured();
        when(jiraService.listBoardSprints(any(), any(), any()))
                .thenReturn(ResponseEntity.status(500).body("{}"));
        mvc.perform(as(get("/api/squads/SQ1/sprint-plans/next-sprint"), "lead")).andExpect(status().isBadGateway());
    }

    @Test
    @DisplayName("limite 1 por 10 s por squad+usuario: segunda chamada 429 'Tente de novo em N s'; outro usuario nao e afetado")
    void nextRateLimit() throws Exception {
        leader();
        jiraConfigured();
        when(access.isSquadLeadership("SQ1", "lead2", "USER")).thenReturn(true);
        when(userJiraConfigRepository.findById("lead2")).thenReturn(Optional.of(
                UserJiraConfig.builder().userId("lead2").token("tok2").domain("totvs.atlassian.net").build()));
        when(jiraService.listBoardSprints(any(), any(), any())).thenReturn(ResponseEntity.ok(SPRINTS));
        mvc.perform(as(get("/api/squads/SQ1/sprint-plans/next-sprint"), "lead")).andExpect(status().isOk());
        mvc.perform(as(get("/api/squads/SQ1/sprint-plans/next-sprint"), "lead"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.matchesPattern("Tente de novo em \\d+ s\\.")));
        mvc.perform(as(get("/api/squads/SQ1/sprint-plans/next-sprint"), "lead2")).andExpect(status().isOk());
        verify(jiraService, times(2)).listBoardSprints(any(), any(), any());
    }

    // ---------------- meta do Jira pelo sync ----------------

    @Test
    @DisplayName("refreshGoals atualiza jira_goal so de planos com sprint do Jira nao enviados; falha de uma nao derruba")
    void refreshGoals() {
        SprintPlanJiraService jira = new SprintPlanJiraService(squadService, jiraService, userJiraConfigRepository,
                planRepository, new ObjectMapper(), 10);
        SprintPlan p1 = SprintPlan.builder().id("A").squadId("SQ1").jiraSprintId("11").status("ESCOPO").jiraGoal("velha")
                .startDate(d(11, 2)).endDate(d(11, 13)).build();
        SprintPlan p2 = SprintPlan.builder().id("B").squadId("SQ1").jiraSprintId("12").status("ENVIADO")
                .startDate(d(11, 16)).endDate(d(11, 27)).build();
        SprintPlan p3 = SprintPlan.builder().id("C").squadId("SQ1").jiraSprintId(null).status("RASCUNHO")
                .startDate(d(12, 1)).endDate(d(12, 10)).build();
        SprintPlan p4 = SprintPlan.builder().id("D").squadId("SQ1").jiraSprintId("13").status("RASCUNHO")
                .startDate(d(12, 14)).endDate(d(12, 24)).build();
        when(planRepository.findBySquadIdOrderByStartDateDesc("SQ1")).thenReturn(List.of(p1, p2, p3, p4));
        when(jiraService.getSprint("dom", "tok", "11")).thenReturn(ResponseEntity.ok("{\"id\":11,\"goal\":\"nova meta\"}"));
        when(jiraService.getSprint("dom", "tok", "13")).thenThrow(new IllegalStateException("x"));
        jira.refreshGoals("SQ1", "dom", "tok");
        assertEquals("nova meta", p1.getJiraGoal());
        verify(planRepository).save(p1);
        verify(jiraService, never()).getSprint("dom", "tok", "12");
        verify(planRepository, never()).save(p4);
    }
}
