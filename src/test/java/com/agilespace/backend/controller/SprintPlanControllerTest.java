package com.agilespace.backend.controller;

import com.agilespace.backend.config.ErrorMessageAdvice;
import com.agilespace.backend.domain.SprintPlan;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.dto.SprintPlanDtos.CapacityView;
import com.agilespace.backend.repository.*;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.SquadAccessService;
import com.agilespace.backend.service.SquadCapacityService;
import com.agilespace.backend.service.sprintplan.SprintPlanCapacityService;
import com.agilespace.backend.service.sprintplan.SprintPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP do Sprint Planner: 403 (membro não edita), 404, 409 (versão/@Version/duplicado/transição), 422 e 501. */
@ExtendWith(MockitoExtension.class)
@DisplayName("SprintPlanController - permissões, conflito e validação")
class SprintPlanControllerTest {

    @Mock SquadAccessService access;
    @Mock SprintPlanRepository planRepository;
    @Mock SprintPlanPersonRepository personRepository;
    @Mock SprintPlanReserveTypeRepository reserveTypeRepository;
    @Mock SprintPlanAbsenceRepository absenceRepository;
    @Mock SprintPlanHolidayRepository holidayRepository;
    @Mock SprintPlanItemRepository itemRepository;
    @Mock SprintPlanAssignmentRepository assignmentRepository;
    @Mock SquadMemberRepository memberRepository;
    @Mock SquadCapacityService squadCapacityService;
    @Mock SprintPlanCapacityService capacityService;

    MockMvc mvc;
    SprintPlan plan;

    @BeforeEach
    void setUp() {
        SprintPlanService service = new SprintPlanService(planRepository, personRepository, reserveTypeRepository,
                absenceRepository, holidayRepository, itemRepository, assignmentRepository, memberRepository,
                squadCapacityService, capacityService);
        mvc = MockMvcBuilders.standaloneSetup(new SprintPlanController(service, access))
                .setControllerAdvice(new ErrorMessageAdvice()).build();

        plan = SprintPlan.builder().id("P1").squadId("SQ1").jiraSprintId("S3").sprintName("Sprint 3")
                .startDate(LocalDate.of(2026, 11, 2)).endDate(LocalDate.of(2026, 11, 13))
                .status(SprintPlan.STATUS_RASCUNHO).reserveMode(SprintPlan.RESERVE_HORAS).version(3L).build();
        lenient().when(planRepository.findById("P1")).thenReturn(Optional.of(plan));
        lenient().when(planRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(capacityService.compute(any())).thenReturn(new CapacityView(Map.of("DEV", 0.0, "QA", 0.0), List.of(), 10));
        lenient().when(capacityService.allocated(any())).thenReturn(Map.of("DEV", 0.0, "QA", 0.0));
        lenient().when(capacityService.thermometer(any(), any(), any())).thenReturn(Map.of());
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String user) {
        return b.requestAttr(JwtAuthenticationFilter.ATTR_USER_ID, user)
                .requestAttr(JwtAuthenticationFilter.ATTR_USER_ROLE, "USER")
                .contentType(MediaType.APPLICATION_JSON);
    }

    private void leader(String user) {
        when(access.isSquadLeadership("SQ1", user, "USER")).thenReturn(true);
    }

    private void member(String user) {
        lenient().when(access.isSquadLeadership("SQ1", user, "USER")).thenReturn(false);
    }

    @Test
    @DisplayName("Membro lê o plano com permissões calculadas (não edita) e com o próprio accountId")
    void memberReads() throws Exception {
        member("dev1");
        when(memberRepository.findByClaimedByUid("dev1")).thenReturn(List.of(
                SquadMember.builder().squadId("SQ1").jiraAccountId("acc-dev1").build()));
        mvc.perform(as(get("/api/squads/SQ1/sprint-plans/P1"), "dev1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.permissions.canEditPlan").value(false))
                .andExpect(jsonPath("$.permissions.canEditTypes").value(false))
                .andExpect(jsonPath("$.permissions.myAccountId").value("acc-dev1"));
    }

    @Test
    @DisplayName("Quem não é da squad: 403 na leitura")
    void outsiderCannotRead() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "x")).when(access).requireSquadReadAccess(eq("SQ1"), any());
        mvc.perform(as(get("/api/squads/SQ1/sprint-plans"), "fora")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Membro sem liderança: 403 em criar, editar, status, capacidade, ausência, feriado, excluir e enviar")
    void memberCannotWrite() throws Exception {
        member("dev1");
        mvc.perform(as(post("/api/squads/SQ1/sprint-plans"), "dev1").content("{}")).andExpect(status().isForbidden());
        mvc.perform(as(patch("/api/squads/SQ1/sprint-plans/P1"), "dev1").content("{\"version\":3}")).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/squads/SQ1/sprint-plans/P1/status"), "dev1").content("{}")).andExpect(status().isForbidden());
        mvc.perform(as(put("/api/squads/SQ1/sprint-plans/P1/capacity/a"), "dev1").content("{}")).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/squads/SQ1/sprint-plans/P1/absences"), "dev1").content("{}")).andExpect(status().isForbidden());
        mvc.perform(as(put("/api/squads/SQ1/sprint-plans/P1/holidays"), "dev1").content("[]")).andExpect(status().isForbidden());
        mvc.perform(as(put("/api/squads/SQ1/sprint-plans/P1/reserve-types"), "dev1").content("[]")).andExpect(status().isForbidden());
        mvc.perform(as(delete("/api/squads/SQ1/sprint-plans/P1"), "dev1")).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/squads/SQ1/sprint-plans/P1/send-to-jira"), "dev1")).andExpect(status().isForbidden());
        verify(planRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Liderança edita: reserva e foco gravam e devolvem o PlanView com canEditPlan")
    void leaderPatches() throws Exception {
        leader("pl");
        mvc.perform(as(patch("/api/squads/SQ1/sprint-plans/P1"), "pl")
                        .content("{\"version\":3,\"reserveMode\":\"FOCO\",\"reservePct\":15,\"focusFactorPct\":70,\"goal\":\"Meta\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reserve.mode").value("FOCO"))
                .andExpect(jsonPath("$.reserve.pct").value(15))
                .andExpect(jsonPath("$.goal").value("Meta"))
                .andExpect(jsonPath("$.permissions.canEditPlan").value(true));
    }

    @Test
    @DisplayName("Plano de outra squad: 404")
    void otherSquadPlan() throws Exception {
        when(access.isSquadLeadership("SQ2", "pl", "USER")).thenReturn(true);
        mvc.perform(as(patch("/api/squads/SQ2/sprint-plans/P1"), "pl").content("{\"version\":3}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Versão velha: 409 sem gravar")
    void staleVersion() throws Exception {
        leader("pl");
        mvc.perform(as(patch("/api/squads/SQ1/sprint-plans/P1"), "pl").content("{\"version\":2,\"goal\":\"x\"}"))
                .andExpect(status().isConflict());
        verify(planRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Corrida detectada pelo @Version no flush: 409")
    void optimisticLock() throws Exception {
        leader("pl");
        when(planRepository.saveAndFlush(any())).thenThrow(new ObjectOptimisticLockingFailureException(SprintPlan.class, "P1"));
        mvc.perform(as(patch("/api/squads/SQ1/sprint-plans/P1"), "pl").content("{\"version\":3,\"goal\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    @DisplayName("Validação 422: sem versão, datas invertidas, reserva > 100, foco 0, modo inválido")
    void validation() throws Exception {
        leader("pl");
        String base = "/api/squads/SQ1/sprint-plans/P1";
        mvc.perform(as(patch(base), "pl").content("{\"goal\":\"x\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(as(patch(base), "pl").content("{\"version\":3,\"endDate\":\"2026-11-01\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(as(patch(base), "pl").content("{\"version\":3,\"reservePct\":101}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(as(patch(base), "pl").content("{\"version\":3,\"focusFactorPct\":0}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(as(patch(base), "pl").content("{\"version\":3,\"reserveMode\":\"XPTO\"}")).andExpect(status().isUnprocessableEntity());
        verify(planRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Criar: 201; mesma sprint de novo: 409; datas inválidas: 422")
    void create() throws Exception {
        leader("pl");
        when(planRepository.existsBySquadIdAndJiraSprintId("SQ1", "S9")).thenReturn(false);
        when(planRepository.existsBySquadIdAndJiraSprintId("SQ1", "S3")).thenReturn(true);
        when(planRepository.findFirstBySquadIdAndStatusInOrderByStartDateDesc(eq("SQ1"), any()))
                .thenReturn(Optional.of(SprintPlan.builder().reservePct(new java.math.BigDecimal("12")).build()));
        mvc.perform(as(post("/api/squads/SQ1/sprint-plans"), "pl")
                        .content("{\"jiraSprintId\":\"S9\",\"sprintName\":\"Sprint 9\",\"startDate\":\"2026-11-16\",\"endDate\":\"2026-11-27\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RASCUNHO"))
                .andExpect(jsonPath("$.reserve.pct").value(12));
        mvc.perform(as(post("/api/squads/SQ1/sprint-plans"), "pl")
                        .content("{\"jiraSprintId\":\"S3\",\"sprintName\":\"Sprint 3\",\"startDate\":\"2026-11-02\",\"endDate\":\"2026-11-13\"}"))
                .andExpect(status().isConflict());
        mvc.perform(as(post("/api/squads/SQ1/sprint-plans"), "pl")
                        .content("{\"sprintName\":\"X\",\"startDate\":\"2026-11-13\",\"endDate\":\"2026-11-02\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Status: avança uma etapa; pular etapa 409; ENVIADO e send-to-jira 501")
    void statusTransitions() throws Exception {
        leader("pl");
        String url = "/api/squads/SQ1/sprint-plans/P1/status";
        mvc.perform(as(post(url), "pl").content("{\"version\":3,\"to\":\"DISTRIBUICAO\"}")).andExpect(status().isConflict());
        mvc.perform(as(post(url), "pl").content("{\"version\":3,\"to\":\"ENVIADO\"}")).andExpect(status().isNotImplemented());
        mvc.perform(as(post(url), "pl").content("{\"version\":3,\"to\":\"ESCOPO\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ESCOPO"));
        mvc.perform(as(post("/api/squads/SQ1/sprint-plans/P1/send-to-jira"), "pl")).andExpect(status().isNotImplemented());
    }

    @Test
    @DisplayName("Ausência fora da sprint e feriado fora da sprint: 422; pessoa fora do time: 422")
    void absenceAndHolidayValidation() throws Exception {
        leader("pl");
        when(memberRepository.findBySquadIdAndJiraAccountId("SQ1", "ana")).thenReturn(Optional.of(SquadMember.builder().build()));
        mvc.perform(as(post("/api/squads/SQ1/sprint-plans/P1/absences"), "pl")
                        .content("{\"accountId\":\"ana\",\"startDate\":\"2026-11-12\",\"endDate\":\"2026-11-16\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(as(post("/api/squads/SQ1/sprint-plans/P1/absences"), "pl")
                        .content("{\"accountId\":\"ninguem\",\"startDate\":\"2026-11-03\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(as(put("/api/squads/SQ1/sprint-plans/P1/holidays"), "pl")
                        .content("[{\"day\":\"2026-12-25\",\"label\":\"Natal\"}]"))
                .andExpect(status().isUnprocessableEntity());
        verify(absenceRepository, never()).saveAndFlush(any());
        verify(holidayRepository, never()).deleteAllForPlan(any());
    }

    @Test
    @DisplayName("Excluir só em rascunho/escopo; depois disso 409")
    void deleteOnlyEarly() throws Exception {
        leader("pl");
        plan.setStatus(SprintPlan.STATUS_REVISAO);
        mvc.perform(as(delete("/api/squads/SQ1/sprint-plans/P1"), "pl")).andExpect(status().isConflict());
        plan.setStatus(SprintPlan.STATUS_ESCOPO);
        mvc.perform(as(delete("/api/squads/SQ1/sprint-plans/P1"), "pl")).andExpect(status().isNoContent());
        verify(planRepository).delete(plan);
    }
}
