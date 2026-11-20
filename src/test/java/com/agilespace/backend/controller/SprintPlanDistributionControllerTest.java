package com.agilespace.backend.controller;

import com.agilespace.backend.config.ErrorMessageAdvice;
import com.agilespace.backend.dto.SprintPlanDistributionDtos.*;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.SquadAccessService;
import com.agilespace.backend.service.sprintplan.SprintPlanDistributionService;
import com.agilespace.backend.service.sprintplan.SprintPlanDistributionService.Caller;
import com.agilespace.backend.service.sprintplan.SprintPlanDistributionService.DistributionRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("SprintPlanDistributionController - contrato JSON, permissões e erros")
class SprintPlanDistributionControllerTest {

    @Mock SprintPlanDistributionService service;
    @Mock SquadAccessService access;

    MockMvc mvc;
    DistributionView view;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SprintPlanDistributionController(service, access))
                .setControllerAdvice(new ErrorMessageAdvice())
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                        org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()))
                .build();
        DistSubtask s = new DistSubtask("A-1-1", "Codificação", "t1", "Codificação", "DEV", false,
                new BigDecimal("8"), new BigDecimal("8"), new BigDecimal("8"), "acc-ana", "Ana",
                LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 4), null, null, false, true, "Aberto");
        view = new DistributionView("P1", "DISTRIBUICAO", "t", true, "acc-ana",
                new SprintInfo(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 13),
                        List.of(new HolidayEntry(LocalDate.of(2026, 11, 20), "Consciência Negra"))),
                List.of(new DistTask("I1", "A-1", "Pai", new BigDecimal("9"), new BigDecimal("5"), List.of(s))),
                List.of(new DistPerson("acc-ana", "Ana", "DEV", 60, 8, 52, 13.33, "VERDE", "DENTRO_DA_CAPACIDADE", List.of())),
                List.of(new DistWarning("SUBTASK_NO_DATES", "msg", "A-1-1", "acc-ana")));
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String user, String role) {
        return b.requestAttr(JwtAuthenticationFilter.ATTR_USER_ID, user).requestAttr(JwtAuthenticationFilter.ATTR_USER_ROLE, role);
    }

    @Test
    @DisplayName("GET devolve o contrato com os nomes JSON combinados")
    void getContract() throws Exception {
        when(access.isSquadLeadership(eq("SQ1"), eq("u1"), any())).thenReturn(true);
        when(service.view(eq("SQ1"), eq("P1"), eq(new Caller("u1", true)))).thenReturn(view);
        mvc.perform(as(get("/api/squads/SQ1/sprint-plans/P1/distribution"), "u1", "MEMBER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value("P1"))
                .andExpect(jsonPath("$.canEditPlan").value(true))
                .andExpect(jsonPath("$.myAccountId").value("acc-ana"))
                .andExpect(jsonPath("$.sprint.startDate").value("2026-11-02"))
                .andExpect(jsonPath("$.sprint.holidays[0].day").value("2026-11-20"))
                .andExpect(jsonPath("$.tasks[0].itemId").value("I1"))
                .andExpect(jsonPath("$.tasks[0].subtasks[0].cls").value("DEV"))
                .andExpect(jsonPath("$.tasks[0].subtasks[0].countedH").value(8))
                .andExpect(jsonPath("$.tasks[0].subtasks[0].startDate").value("2026-11-02"))
                .andExpect(jsonPath("$.tasks[0].subtasks[0].canEditDates").value(true))
                .andExpect(jsonPath("$.people[0].zone").value("VERDE"))
                .andExpect(jsonPath("$.people[0].freeHours").value(52.0))
                .andExpect(jsonPath("$.warnings[0].code").value("SUBTASK_NO_DATES"));
    }

    @Test
    @DisplayName("quem não é da squad leva 403 em qualquer rota")
    void noReadAccess() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a membros desta squad ou administradores."))
                .when(access).requireSquadReadAccess(eq("SQ1"), any());
        mvc.perform(as(get("/api/squads/SQ1/sprint-plans/P1/distribution"), "u9", "MEMBER")).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/squads/SQ1/sprint-plans/P1/distribution/claim").contentType(MediaType.APPLICATION_JSON)
                .content("{\"subtaskKey\":\"A-1-1\"}"), "u9", "MEMBER")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("POST claim, DELETE claim e PUT dates chamam o serviço com o corpo e quem chama")
    void routes() throws Exception {
        when(service.claim(eq("SQ1"), eq("P1"), eq(new ClaimRequest("A-1-1", "acc-b")), eq(new Caller("u1", false)))).thenReturn(view);
        when(service.release("SQ1", "P1", "A-1-1", new Caller("u1", false))).thenReturn(view);
        when(service.setDates("SQ1", "P1", "A-1-1", new DatesRequest("2026-11-02", "2026-11-04"), new Caller("u1", false)))
                .thenReturn(view);
        mvc.perform(as(post("/api/squads/SQ1/sprint-plans/P1/distribution/claim").contentType(MediaType.APPLICATION_JSON)
                .content("{\"subtaskKey\":\"A-1-1\",\"accountId\":\"acc-b\"}"), "u1", "MEMBER")).andExpect(status().isOk());
        mvc.perform(as(delete("/api/squads/SQ1/sprint-plans/P1/distribution/claim/A-1-1"), "u1", "MEMBER")).andExpect(status().isOk());
        mvc.perform(as(put("/api/squads/SQ1/sprint-plans/P1/distribution/dates/A-1-1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"startDate\":\"2026-11-02\",\"endDate\":\"2026-11-04\"}"), "u1", "MEMBER")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("erros do serviço chegam com a mensagem em português: 403, 409, 422 e 422 da cadeia com minStartDate")
    void errorMapping() throws Exception {
        when(service.claim(any(), any(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Essa subtarefa já tem responsável."));
        mvc.perform(as(post("/api/squads/SQ1/sprint-plans/P1/distribution/claim").contentType(MediaType.APPLICATION_JSON)
                .content("{\"subtaskKey\":\"A-1-1\"}"), "u1", "MEMBER"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Essa subtarefa já tem responsável."));

        when(service.setDates(any(), any(), eq("A-1-2"), any(), any()))
                .thenThrow(new DistributionRuleException("O início não pode ser antes de 04/11.", LocalDate.of(2026, 11, 4)));
        mvc.perform(as(put("/api/squads/SQ1/sprint-plans/P1/distribution/dates/A-1-2").contentType(MediaType.APPLICATION_JSON)
                .content("{\"startDate\":\"2026-11-03\",\"endDate\":\"2026-11-05\"}"), "u1", "MEMBER"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("O início não pode ser antes de 04/11."))
                .andExpect(jsonPath("$.minStartDate").value("2026-11-04"));

        when(service.setDates(any(), any(), eq("A-1-3"), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Só quem assumiu a subtarefa pode marcar as datas."));
        mvc.perform(as(put("/api/squads/SQ1/sprint-plans/P1/distribution/dates/A-1-3").contentType(MediaType.APPLICATION_JSON)
                .content("{\"startDate\":\"2026-11-03\",\"endDate\":\"2026-11-05\"}"), "u1", "MEMBER"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("lock otimista ou violação única que escapa do serviço também vira 409")
    void optimisticLockIs409() throws Exception {
        when(service.setDates(any(), any(), any(), any(), any()))
                .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(Object.class, "x"));
        mvc.perform(as(put("/api/squads/SQ1/sprint-plans/P1/distribution/dates/A-1-1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"startDate\":\"2026-11-03\",\"endDate\":\"2026-11-05\"}"), "u1", "MEMBER"))
                .andExpect(status().isConflict());
    }
}
