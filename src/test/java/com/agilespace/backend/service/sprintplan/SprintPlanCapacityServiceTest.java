package com.agilespace.backend.service.sprintplan;

import com.agilespace.backend.domain.*;
import com.agilespace.backend.dto.SprintPlanDtos.CapacityRow;
import com.agilespace.backend.dto.SprintPlanDtos.CapacityView;
import com.agilespace.backend.repository.*;
import com.agilespace.backend.service.SquadCapacityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Capacidade do plano com o SquadCapacityService REAL (repos mockados): a herança sprint anterior → GLOBAL vem de lá,
 * o plano só aplica feriado, ausência, inclusão e foco.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SprintPlanCapacityService - capacidade do plano sobre squad_person_configs")
class SprintPlanCapacityServiceTest {

    @Mock SquadPersonConfigRepository configRepository;
    @Mock SquadRepository squadRepository;
    @Mock SquadMemberRepository memberRepository;
    @Mock SprintPlanPersonRepository personRepository;
    @Mock SprintPlanAbsenceRepository absenceRepository;
    @Mock SprintPlanHolidayRepository holidayRepository;

    SprintPlanCapacityService service;
    SprintPlan plan;

    @BeforeEach
    void setUp() {
        service = new SprintPlanCapacityService(new SquadCapacityService(configRepository, squadRepository),
                memberRepository, personRepository, absenceRepository, holidayRepository);
        plan = SprintPlan.builder().id("P1").squadId("SQ1").jiraSprintId("S3").sprintName("Sprint 3")
                .startDate(LocalDate.of(2026, 11, 2)).endDate(LocalDate.of(2026, 11, 13))
                .status(SprintPlan.STATUS_RASCUNHO).reserveMode(SprintPlan.RESERVE_HORAS).version(0L).build();

        ArrayNode history = new ObjectMapper().createArrayNode();
        history.addObject().put("sprintId", "S1").put("sprintStart", "2026-10-05");
        history.addObject().put("sprintId", "S2").put("sprintStart", "2026-10-19");
        lenient().when(squadRepository.findById("SQ1")).thenReturn(Optional.of(Squad.builder().id("SQ1").sprintHistory(history).build()));
        lenient().when(configRepository.findBySquadIdAndSprintIdAndJiraAccountId(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(memberRepository.findBySquadIdOrderByDisplayNameAsc("SQ1")).thenReturn(List.of(
                SquadMember.builder().squadId("SQ1").jiraAccountId("ana").displayName("Ana").build(),
                SquadMember.builder().squadId("SQ1").jiraAccountId("bia").displayName("Bia").build()));
        lenient().when(personRepository.findByPlanId("P1")).thenReturn(List.of());
        lenient().when(absenceRepository.findByPlanIdOrderByStartDateAsc("P1")).thenReturn(List.of());
        lenient().when(holidayRepository.findByPlanIdOrderByDayAsc("P1")).thenReturn(List.of());
    }

    private void config(String sprint, String acc, String papel, Integer cod, Integer reg, Double h) {
        when(configRepository.findBySquadIdAndSprintIdAndJiraAccountId("SQ1", sprint, acc)).thenReturn(Optional.of(
                SquadPersonConfig.builder().squadId("SQ1").sprintId(sprint).jiraAccountId(acc)
                        .papel(papel).diasCodificacaoTeste(cod).diasRegressivo(reg).horasProdutivas(h).build()));
    }

    private CapacityRow row(CapacityView v, String acc) {
        return v.people().stream().filter(r -> r.accountId().equals(acc)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Herda da sprint anterior mais recente (S2) e do GLOBAL, campo a campo, sem duplicar no plano")
    void inheritsFromPreviousSprint() {
        config("S2", "ana", "DEV", 8, null, 6.0);
        config("GLOBAL", "ana", null, null, 2, null);
        config("S3", "bia", "QA", 9, 1, null); // sem horas: padrão 8

        CapacityView v = service.compute(plan);

        CapacityRow ana = row(v, "ana");
        assertEquals("DEV", ana.papel());
        assertTrue(ana.papelInherited());
        assertEquals("S2", ana.papelSource());
        assertEquals("GLOBAL", ana.diasRegSource());
        assertEquals((8 + 2) * 6.0, ana.capacityHours());
        assertEquals(80.0, row(v, "bia").capacityHours());
        assertEquals(60.0, v.byClass().get("DEV"));
        assertEquals(80.0, v.byClass().get("QA"));
        assertEquals(10, v.workingDays());
    }

    @Test
    @DisplayName("Feriado e ausência do plano descontam; pessoa fora do plano não soma; foco só no modo FOCO")
    void holidaysAbsencesIncludedAndFocus() {
        config("S3", "ana", "DEV", 8, 2, 8.0);
        config("S3", "bia", "DEV", 10, 0, 8.0);
        when(holidayRepository.findByPlanIdOrderByDayAsc("P1")).thenReturn(List.of(
                SprintPlanHoliday.builder().planId("P1").day(LocalDate.of(2026, 11, 2)).build()));
        when(absenceRepository.findByPlanIdOrderByStartDateAsc("P1")).thenReturn(List.of(
                SprintPlanAbsence.builder().planId("P1").jiraAccountId("ana")
                        .startDate(LocalDate.of(2026, 11, 3)).endDate(LocalDate.of(2026, 11, 3))
                        .hoursPerDay(new BigDecimal("4")).build()));
        when(personRepository.findByPlanId("P1")).thenReturn(List.of(
                SprintPlanPerson.builder().planId("P1").jiraAccountId("bia").included(false).build()));

        CapacityView horas = service.compute(plan);
        assertEquals(80 - 8 - 4, row(horas, "ana").capacityHours());
        assertNull(row(horas, "ana").focusPct());
        assertFalse(row(horas, "bia").included());
        assertEquals(68.0, horas.byClass().get("DEV"), "bia está fora do plano");

        plan.setReserveMode(SprintPlan.RESERVE_FOCO);
        plan.setFocusFactorPct(new BigDecimal("50"));
        CapacityView foco = service.compute(plan);
        assertEquals(34.0, row(foco, "ana").capacityHours());
        assertEquals(50.0, row(foco, "ana").focusPct());
    }

    @Test
    @DisplayName("Termômetro usa a reserva do plano sobre o alocado informado")
    void thermometerUsesPlanReserve() {
        config("S3", "ana", "DEV", 10, 0, 10.0);
        plan.setReservePct(new BigDecimal("10"));

        CapacityView cap = service.compute(plan);
        var t = service.thermometer(plan, cap, Map.of("DEV", 105.0, "QA", 5.0));
        assertEquals(SprintPlanCalculator.State.COBERTO_PELA_RESERVA, t.get("DEV").state());
        assertEquals(SprintPlanCalculator.State.SEM_CAPACIDADE, t.get("QA").state());
    }
}
