package com.agilespace.backend.controller;

import com.agilespace.backend.config.ErrorMessageAdvice;
import com.agilespace.backend.domain.SprintPlan;
import com.agilespace.backend.domain.SprintPlanItem;
import com.agilespace.backend.domain.SubtaskClass;
import com.agilespace.backend.dto.SprintPlanDtos.CapacityView;
import com.agilespace.backend.repository.SprintPlanItemRepository;
import com.agilespace.backend.repository.SprintPlanRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.PlannerBacklogService;
import com.agilespace.backend.service.PlannerBacklogService.HoursPair;
import com.agilespace.backend.service.PlannerBacklogService.ParentView;
import com.agilespace.backend.service.SquadAccessService;
import com.agilespace.backend.service.sprintplan.SprintPlanCapacityService;
import com.agilespace.backend.service.sprintplan.SprintPlanScopeService;
import com.agilespace.backend.service.sprintplan.SprintPlanService;
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
import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP do escopo: membro lê, só liderança edita (403), duplicada 409, etapa errada 422. */
@ExtendWith(MockitoExtension.class)
@DisplayName("SprintPlanController (escopo) - permissões, 409 e 422")
class SprintPlanScopeControllerTest {

    @Mock SquadAccessService access;
    @Mock SprintPlanRepository planRepository;
    @Mock SprintPlanItemRepository itemRepository;
    @Mock PlannerBacklogService backlogService;
    @Mock SprintPlanCapacityService capacityService;
    @Mock SprintPlanService planService;
    @Mock com.agilespace.backend.service.sprintplan.SprintPlanDistributionService distributionService;

    MockMvc mvc;
    SprintPlan plan;
    final List<SprintPlanItem> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        SprintPlanScopeService scope = new SprintPlanScopeService(planRepository, itemRepository, backlogService, capacityService);
        mvc = MockMvcBuilders.standaloneSetup(new SprintPlanController(planService, scope, distributionService, access))
                .setControllerAdvice(new ErrorMessageAdvice()).build();
        plan = SprintPlan.builder().id("P1").squadId("SQ1").jiraSprintId("S3").sprintName("Sprint 3")
                .startDate(LocalDate.of(2026, 11, 2)).endDate(LocalDate.of(2026, 11, 13))
                .status(SprintPlan.STATUS_ESCOPO).reserveMode(SprintPlan.RESERVE_HORAS).version(1L).build();
        lenient().when(planRepository.findById("P1")).thenReturn(Optional.of(plan));
        lenient().when(planRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(capacityService.compute(any())).thenReturn(new CapacityView(Map.of("DEV", 90.0, "QA", 75.0), List.of(), 10));
        lenient().when(capacityService.thermometer(any(), any(), any())).thenReturn(Map.of());
        lenient().when(itemRepository.findByPlanIdOrderBySortOrderAsc("P1")).thenAnswer(i -> new ArrayList<>(stored));
        lenient().when(itemRepository.save(any())).thenAnswer(i -> {
            stored.add(i.getArgument(0));
            return i.getArgument(0);
        });
        Map<SubtaskClass, BigDecimal> sums = new EnumMap<>(SubtaskClass.class);
        ParentView p = new ParentView("A-1", "Pai (DEV 10h / QA 4h)", "Refinamento Concluído", false,
                new HoursPair(new BigDecimal("10"), new BigDecimal("4")), List.of(), sums, false, false, false, Map.of(), false);
        lenient().when(backlogService.view(eq("SQ1"), any(), any(), eq(false))).thenReturn(
                new PlannerBacklogService.BacklogView("t", "S3", List.of(p), List.of()));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String user) {
        return b.requestAttr(JwtAuthenticationFilter.ATTR_USER_ID, user)
                .requestAttr(JwtAuthenticationFilter.ATTR_USER_ROLE, "USER")
                .contentType(MediaType.APPLICATION_JSON);
    }

    private void leader(String u) {
        lenient().when(access.isSquadLeadership("SQ1", u, "USER")).thenReturn(true);
    }

    private void member(String u) {
        lenient().when(access.isSquadLeadership("SQ1", u, "USER")).thenReturn(false);
    }

    private static final String URL = "/api/squads/SQ1/sprint-plans/P1/items";

    @Test
    @DisplayName("Membro lê o escopo mas não edita: POST, DELETE e PUT de ordem dão 403")
    void memberReadsButCannotEdit() throws Exception {
        member("dev1");
        mvc.perform(as(get(URL), "dev1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.canEditPlan").value(false))
                .andExpect(jsonPath("$.totals.itemCount").value(0));
        mvc.perform(as(post(URL), "dev1").content("{\"parentKey\":\"A-1\"}")).andExpect(status().isForbidden());
        mvc.perform(as(delete(URL + "/I1"), "dev1")).andExpect(status().isForbidden());
        mvc.perform(as(put(URL + "/order"), "dev1").content("{\"orderedItemIds\":[]}")).andExpect(status().isForbidden());
        verify(itemRepository, never()).save(any());
    }

    @Test
    @DisplayName("Quem não é da squad: 403 na leitura")
    void outsiderCannotRead() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "x")).when(access).requireSquadReadAccess(eq("SQ1"), any());
        mvc.perform(as(get(URL), "fora")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Liderança inclui (201); repetir a pai dá 409; pai fora do backlog e corpo vazio dão 422")
    void leaderAddsThenConflict() throws Exception {
        leader("pl");
        mvc.perform(as(post(URL), "pl").content("{\"parentKey\":\"A-1\"}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].parentKey").value("A-1"))
                .andExpect(jsonPath("$.items[0].devHours").value(11))
                .andExpect(jsonPath("$.items[0].codeReviewDevH").value(1))
                .andExpect(jsonPath("$.totals.allocated.DEV").value(11.0));
        mvc.perform(as(post(URL), "pl").content("{\"parentKey\":\"A-1\"}")).andExpect(status().isConflict());
        mvc.perform(as(post(URL), "pl").content("{\"parentKey\":\"Z-9\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(as(post(URL), "pl").content("{}")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Plano na Distribuição: incluir pai dá 422")
    void wrongStage() throws Exception {
        leader("pl");
        plan.setStatus(SprintPlan.STATUS_DISTRIBUICAO);
        mvc.perform(as(post(URL), "pl").content("{\"parentKey\":\"A-1\"}")).andExpect(status().isUnprocessableEntity());
    }
}
