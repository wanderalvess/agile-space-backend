package com.agilespace.backend.service.sprintplan;

import com.agilespace.backend.service.sprintplan.SprintPlanCalculator.Absence;
import com.agilespace.backend.service.sprintplan.SprintPlanCalculator.State;
import com.agilespace.backend.service.sprintplan.SprintPlanCalculator.Zone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Regras da seção 5 do plano técnico com os padrões de produto 1 e 2 (ver javadoc de SprintPlanCalculator). */
@DisplayName("SprintPlanCalculator - capacidade, feriado, ausência, foco e termômetro")
class SprintPlanCalculatorTest {

    // Sprint de 2 semanas: seg 2026-11-02 a sex 2026-11-13 (10 dias úteis).
    private static final LocalDate START = LocalDate.of(2026, 11, 2);
    private static final LocalDate END = LocalDate.of(2026, 11, 13);

    @Test
    @DisplayName("Dias úteis: seg–sex menos feriados do plano")
    void workingDays() {
        assertEquals(10, SprintPlanCalculator.workingDays(START, END, Set.of()));
        assertEquals(9, SprintPlanCalculator.workingDays(START, END, Set.of(LocalDate.of(2026, 11, 2))));
        // Feriado em sábado não muda nada.
        assertEquals(10, SprintPlanCalculator.workingDays(START, END, Set.of(LocalDate.of(2026, 11, 7))));
    }

    @Test
    @DisplayName("Sem feriado/ausência/foco: capacidade = (diasCod + diasReg) × h")
    void baseCapacity() {
        var cap = SprintPlanCalculator.personCapacity(10 * 6.0, 6.0, START, END, Set.of(), List.of(), null);
        assertEquals(60.0, cap.capacityHours());
        assertEquals(100.0, cap.focusPct());
    }

    @Test
    @DisplayName("Decisão 1: feriado em dia útil desconta h; feriado no fim de semana ou fora da sprint não")
    void holidaysDiscount() {
        Set<LocalDate> holidays = Set.of(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 8), LocalDate.of(2026, 12, 25));
        var cap = SprintPlanCalculator.personCapacity(80, 8, START, END, holidays, List.of(), null);
        assertEquals(8.0, cap.holidaysHours());
        assertEquals(72.0, cap.capacityHours());
    }

    @Test
    @DisplayName("Ausência dia inteiro = h por dia útil; parcial = min(hoursPerDay, h); não conta fim de semana nem feriado")
    void absences() {
        Set<LocalDate> holidays = Set.of(LocalDate.of(2026, 11, 6)); // sexta
        List<Absence> abs = List.of(
                new Absence(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 9), null), // qui, (sex feriado), sáb, dom, seg = 2 dias
                new Absence(LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 11), 4.0), // meio dia
                new Absence(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 12), 12.0)); // limitado a h
        var cap = SprintPlanCalculator.personCapacity(80, 8, START, END, holidays, abs, null);
        assertEquals(8.0, cap.holidaysHours());
        assertEquals(2 * 8 + 4 + 8, cap.absencesHours());
        assertEquals(80 - 8 - 28, cap.capacityHours());
    }

    @Test
    @DisplayName("Ausência que passa da sprint conta só a interseção")
    void absenceClippedToSprint() {
        List<Absence> abs = List.of(new Absence(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 3), null));
        assertEquals(16.0, SprintPlanCalculator.absenceHours(START, END, Set.of(), abs, 8));
    }

    @Test
    @DisplayName("Capacidade nunca fica negativa")
    void neverNegative() {
        List<Absence> abs = List.of(new Absence(START, END, null));
        assertEquals(0.0, SprintPlanCalculator.personCapacity(16, 8, START, END, Set.of(), abs, null).capacityHours());
    }

    @Test
    @DisplayName("Decisão 2: foco aplicado à capacidade já descontada; pessoa sobrepõe time; padrão 100; só no modo FOCO")
    void focus() {
        assertNull(SprintPlanCalculator.effectiveFocus(false, 50.0, 70.0));
        assertEquals(50.0, SprintPlanCalculator.effectiveFocus(true, 50.0, 70.0));
        assertEquals(70.0, SprintPlanCalculator.effectiveFocus(true, null, 70.0));
        assertEquals(100.0, SprintPlanCalculator.effectiveFocus(true, null, null));

        Set<LocalDate> holidays = Set.of(LocalDate.of(2026, 11, 2));
        var cap = SprintPlanCalculator.personCapacity(80, 8, START, END, holidays, List.of(), 70.0);
        assertEquals(72 * 0.7, cap.capacityHours(), 1e-9);
    }

    @ParameterizedTest(name = "{0}h de 100h -> {1}")
    @CsvSource({
            "84.99, VERDE",
            "85, AMARELO",
            "95, AMARELO",
            "95.01, VERMELHO",
            "100, VERMELHO",
    })
    @DisplayName("Termômetro: verde < 85, amarelo 85–95, vermelho > 95")
    void zones(double allocated, Zone zone) {
        var t = SprintPlanCalculator.thermometer(allocated, 100, 10.0);
        assertEquals(zone, t.zone());
        assertEquals(State.DENTRO_DA_CAPACIDADE, t.state());
    }

    @Test
    @DisplayName("Decisão 2: acima de 100% e até capacidade × (1 + reserva) = coberto; além = acima da reserva (não bloqueia)")
    void reserveIsALimit() {
        assertEquals(State.COBERTO_PELA_RESERVA, SprintPlanCalculator.thermometer(100.01, 100, 10.0).state());
        assertEquals(State.COBERTO_PELA_RESERVA, SprintPlanCalculator.thermometer(110, 100, 10.0).state());
        assertEquals(State.ACIMA_DA_RESERVA, SprintPlanCalculator.thermometer(110.01, 100, 10.0).state());
        assertEquals(110.0, SprintPlanCalculator.thermometer(0, 100, 10.0).reserveLimitHours());
        // Sem reserva informada: qualquer coisa acima de 100% já passou da reserva.
        assertEquals(State.ACIMA_DA_RESERVA, SprintPlanCalculator.thermometer(101, 100, null).state());
        assertEquals(Zone.VERMELHO, SprintPlanCalculator.thermometer(150, 100, 10.0).zone());
    }

    @Test
    @DisplayName("Capacidade zero: sem capacidade, sem porcentagem")
    void noCapacity() {
        var t = SprintPlanCalculator.thermometer(10, 0, 10.0);
        assertNull(t.pct());
        assertEquals(Zone.SEM_CAPACIDADE, t.zone());
        assertEquals(State.SEM_CAPACIDADE, t.state());
    }
}
