package com.agilespace.backend.service.sprintplan;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Set;

/**
 * Regras de cálculo puras do Sprint Planner (plano técnico, seção 5). Sem banco nem Spring: cada regra é testada
 * direto em SprintPlanCalculatorTest. As decisões de produto ficam nas constantes e nos comentários abaixo, para
 * mudar num lugar só.
 *
 * <ul>
 *   <li><b>Decisão 1 (feriados/ausências descontam):</b> diasCod/diasReg informados são os dias da sprint ANTES de
 *   feriados e ausências. Cada feriado do plano em dia útil (seg–sex) dentro da sprint tira as horas produtivas de um
 *   dia; cada dia útil não feriado de ausência tira {@code min(hoursPerDay, h)} (dia inteiro = h). Para voltar à
 *   regra "dias já descontam feriados", basta {@link #HOLIDAYS_DISCOUNT_CAPACITY} = false.</li>
 *   <li><b>Decisão 2 (reserva é limite, foco é aplicado):</b> a reserva NÃO sai da capacidade; ela só define até
 *   onde a alocação acima de 100% é "coberta" ({@code alocado <= capacidade * (1 + reserva%)}). No modo FOCO a
 *   capacidade é multiplicada pelo fator de foco (da pessoa, senão do time, senão 100%). Tipos FORA/SEM não entram no
 *   alocado (quem soma o alocado filtra por classe).</li>
 *   <li><b>Termômetro:</b> verde &lt; 85%, amarelo 85–95%, vermelho &gt; 95%; acima de 100% e dentro da reserva =
 *   COBERTO_PELA_RESERVA; além = ACIMA_DA_RESERVA. Nunca bloqueia.</li>
 * </ul>
 */
public final class SprintPlanCalculator {

    private SprintPlanCalculator() {}

    /** Decisão 1. false = os dias informados já excluem feriados (feriadoH = 0). */
    public static final boolean HOLIDAYS_DISCOUNT_CAPACITY = true;

    public static final double GREEN_BELOW_PCT = 85.0;
    public static final double YELLOW_UP_TO_PCT = 95.0;

    public enum Zone { VERDE, AMARELO, VERMELHO, SEM_CAPACIDADE }

    public enum State { DENTRO_DA_CAPACIDADE, COBERTO_PELA_RESERVA, ACIMA_DA_RESERVA, SEM_CAPACIDADE }

    public record Absence(LocalDate start, LocalDate end, Double hoursPerDay) {}

    public record PersonCapacity(double baseHours, double holidaysHours, double absencesHours,
                                 double focusPct, double capacityHours) {}

    public record Thermometer(double allocatedHours, double capacityHours, Double pct, Zone zone, State state,
                              double reservePct, double reserveLimitHours) {}

    public static boolean isWeekday(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY;
    }

    /** DIAS_UTEIS(plan): seg–sex entre início e fim (inclusive), menos feriados do plano. */
    public static int workingDays(LocalDate start, LocalDate end, Set<LocalDate> holidays) {
        int n = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (isWeekday(d) && !holidays.contains(d)) n++;
        }
        return n;
    }

    /** Feriados em dia útil dentro da sprint. */
    public static int holidaysOnWorkingDays(LocalDate start, LocalDate end, Set<LocalDate> holidays) {
        return (int) holidays.stream()
                .filter(d -> !d.isBefore(start) && !d.isAfter(end) && isWeekday(d))
                .count();
    }

    /** Horas de ausência: por dia útil, não feriado, na interseção ausência × sprint. */
    public static double absenceHours(LocalDate sprintStart, LocalDate sprintEnd, Set<LocalDate> holidays,
                                      Collection<Absence> absences, double horasProdutivas) {
        double total = 0;
        for (Absence a : absences) {
            LocalDate from = a.start().isBefore(sprintStart) ? sprintStart : a.start();
            LocalDate to = a.end().isAfter(sprintEnd) ? sprintEnd : a.end();
            double perDay = a.hoursPerDay() == null ? horasProdutivas : Math.min(a.hoursPerDay(), horasProdutivas);
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                if (isWeekday(d) && !holidays.contains(d)) total += perDay;
            }
        }
        return total;
    }

    /**
     * CAP_PESSOA. {@code baseHours} = (diasCod + diasReg) × h, como ResolvedPersonConfig.capacityHours().
     * {@code focusPct} só vale no modo FOCO (null no modo HORAS).
     */
    public static PersonCapacity personCapacity(double baseHours, double horasProdutivas,
                                                LocalDate sprintStart, LocalDate sprintEnd, Set<LocalDate> holidays,
                                                Collection<Absence> absences, Double focusPct) {
        double holidaysH = HOLIDAYS_DISCOUNT_CAPACITY
                ? holidaysOnWorkingDays(sprintStart, sprintEnd, holidays) * horasProdutivas
                : 0;
        double absH = absenceHours(sprintStart, sprintEnd, holidays, absences, horasProdutivas);
        double gross = Math.max(0, baseHours - holidaysH - absH);
        double focus = focusPct == null ? 100.0 : focusPct;
        return new PersonCapacity(baseHours, holidaysH, absH, focus, round2(gross * focus / 100.0));
    }

    /** Fator de foco efetivo: só no modo FOCO; pessoa sobrepõe time; padrão 100. */
    public static Double effectiveFocus(boolean focoMode, Double personFocus, Double teamFocus) {
        if (!focoMode) return null;
        if (personFocus != null) return personFocus;
        if (teamFocus != null) return teamFocus;
        return 100.0;
    }

    /** TERMOMETRO(classe). {@code reservePct} null = 0. */
    public static Thermometer thermometer(double allocated, double capacity, Double reservePct) {
        double reserve = reservePct == null ? 0 : reservePct;
        double limitHours = round2(capacity * (1 + reserve / 100.0));
        if (capacity <= 0) {
            return new Thermometer(allocated, capacity, null, Zone.SEM_CAPACIDADE, State.SEM_CAPACIDADE, reserve, limitHours);
        }
        double pct = round2(allocated / capacity * 100.0);
        Zone zone = pct < GREEN_BELOW_PCT ? Zone.VERDE : pct <= YELLOW_UP_TO_PCT ? Zone.AMARELO : Zone.VERMELHO;
        State state;
        if (pct <= 100.0) {
            state = State.DENTRO_DA_CAPACIDADE;
        } else if (allocated <= capacity * (1 + reserve / 100.0) + 1e-9) {
            state = State.COBERTO_PELA_RESERVA;
        } else {
            state = State.ACIMA_DA_RESERVA;
        }
        return new Thermometer(allocated, capacity, pct, zone, state, reserve, limitHours);
    }

    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
