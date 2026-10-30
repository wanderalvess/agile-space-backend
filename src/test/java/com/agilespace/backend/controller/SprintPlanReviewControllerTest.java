package com.agilespace.backend.controller;

import com.agilespace.backend.config.ErrorMessageAdvice;
import com.agilespace.backend.dto.SprintPlanReviewDtos.*;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.SquadAccessService;
import com.agilespace.backend.service.sprintplan.SprintPlanDistributionService;
import com.agilespace.backend.service.sprintplan.SprintPlanReviewService;
import com.agilespace.backend.service.sprintplan.SprintPlanScopeService;
import com.agilespace.backend.service.sprintplan.SprintPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("GET /sprint-plans/{id}/review - contrato e permissões")
class SprintPlanReviewControllerTest {

    @Mock SprintPlanService planService;
    @Mock SprintPlanScopeService scopeService;
    @Mock SprintPlanDistributionService distributionService;
    @Mock SprintPlanReviewService reviewService;
    @Mock SquadAccessService access;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SprintPlanController(planService, scopeService, distributionService,
                        reviewService, access))
                .setControllerAdvice(new ErrorMessageAdvice()).build();
    }

    private ReviewView view() {
        return new ReviewView("P1", "REVISAO", "sync", false, new ReviewSprint("Sprint 3", LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 13)), "meta", false, List.of(), List.of(),
                List.of(new Pending("SUBTASK_NO_DATES", "BLOCKING", "m", "A-1-1", "acc", "I1")), 1, 0, false, false,
                new JiraPreview("Regras de escrita no Jira em definição", new GoalChange(null, "meta", "SET"), List.of(),
                        new PreviewCounts(0, 0, 0, 0)), null);
    }

    @Test
    @DisplayName("membro lê: 200 com o contrato")
    void memberReads() throws Exception {
        when(reviewService.review("SQ1", "P1", false)).thenReturn(view());
        mvc.perform(get("/api/squads/SQ1/sprint-plans/P1/review")
                        .requestAttr(JwtAuthenticationFilter.ATTR_USER_ID, "u1")
                        .requestAttr(JwtAuthenticationFilter.ATTR_USER_ROLE, "MEMBER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value("P1"))
                .andExpect(jsonPath("$.blockingCount").value(1))
                .andExpect(jsonPath("$.canSend").value(false))
                .andExpect(jsonPath("$.writeRulesDefined").value(false))
                .andExpect(jsonPath("$.pendings[0].severity").value("BLOCKING"))
                .andExpect(jsonPath("$.jiraPreview.sprintGoal.kind").value("SET"));
    }

    @Test
    @DisplayName("não-membro: 403")
    void nonMember() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito."))
                .when(access).requireSquadReadAccess(eq("SQ1"), any());
        mvc.perform(get("/api/squads/SQ1/sprint-plans/P1/review")).andExpect(status().isForbidden());
        verifyNoInteractions(reviewService);
    }

    @Test
    @DisplayName("plano inexistente: 404")
    void notFound() throws Exception {
        when(reviewService.review(eq("SQ1"), eq("PX"), eq(false)))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Plano não encontrado."));
        mvc.perform(get("/api/squads/SQ1/sprint-plans/PX/review")).andExpect(status().isNotFound());
    }
}
