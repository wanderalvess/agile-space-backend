package com.agilespace.backend.service.sprintplan;

import com.agilespace.backend.domain.*;
import com.agilespace.backend.dto.SprintPlanDtos.CapacityRow;
import com.agilespace.backend.dto.SprintPlanDtos.CapacityView;
import com.agilespace.backend.repository.*;
import com.agilespace.backend.service.SquadCapacityService;
import com.agilespace.backend.service.SquadCapacityService.ResolvedPersonConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Capacidade do plano (CAP_PESSOA / CAP_CLASSE). Papel, dias e horas produtivas vêm do {@link SquadCapacityService}
 * (squad_person_configs, com a herança sprint atual → anteriores → GLOBAL → 8h); o plano só acrescenta o que é dele:
 * inclusão, foco por pessoa, ausências e feriados. As contas ficam em {@link SprintPlanCalculator}.
 */
@Service
@RequiredArgsConstructor
public class SprintPlanCapacityService {

    public static final String CLASS_DEV = "DEV";
    public static final String CLASS_QA = "QA";

    private final SquadCapacityService squadCapacityService;
    private final SquadMemberRepository squadMemberRepository;
    private final SprintPlanPersonRepository personRepository;
    private final SprintPlanAbsenceRepository absenceRepository;
    private final SprintPlanHolidayRepository holidayRepository;

    @Transactional(readOnly = true)
    public CapacityView compute(SprintPlan plan) {
        Set<LocalDate> holidays = holidayRepository.findByPlanIdOrderByDayAsc(plan.getId()).stream()
                .map(SprintPlanHoliday::getDay).collect(Collectors.toCollection(TreeSet::new));
        Map<String, SprintPlanPerson> overrides = personRepository.findByPlanId(plan.getId()).stream()
                .collect(Collectors.toMap(SprintPlanPerson::getJiraAccountId, p -> p, (a, b) -> a));
        Map<String, List<SprintPlanCalculator.Absence>> absences = new HashMap<>();
        for (SprintPlanAbsence a : absenceRepository.findByPlanIdOrderByStartDateAsc(plan.getId())) {
            absences.computeIfAbsent(a.getJiraAccountId(), k -> new ArrayList<>()).add(new SprintPlanCalculator.Absence(
                    a.getStartDate(), a.getEndDate(), a.getHoursPerDay() == null ? null : a.getHoursPerDay().doubleValue()));
        }
        boolean foco = SprintPlan.RESERVE_FOCO.equals(plan.getReserveMode());
        Double teamFocus = toDouble(plan.getFocusFactorPct());

        List<CapacityRow> rows = new ArrayList<>();
        double dev = 0, qa = 0;
        for (SquadMember m : squadMemberRepository.findBySquadIdOrderByDisplayNameAsc(plan.getSquadId())) {
            String acc = m.getJiraAccountId();
            ResolvedPersonConfig cfg = squadCapacityService.resolve(plan.getSquadId(), plan.sprintKey(), acc);
            SprintPlanPerson ov = overrides.get(acc);
            boolean included = ov == null || ov.isIncluded();
            BigDecimal personFocus = ov == null ? null : ov.getFocusFactorPct();
            Double focus = SprintPlanCalculator.effectiveFocus(foco, toDouble(personFocus), teamFocus);
            SprintPlanCalculator.PersonCapacity cap = SprintPlanCalculator.personCapacity(
                    cfg.capacityHours(), cfg.horasProdutivas(), plan.getStartDate(), plan.getEndDate(), holidays,
                    absences.getOrDefault(acc, List.of()), focus);
            rows.add(new CapacityRow(acc, m.getDisplayName(), cfg.papel(), cfg.diasCodificacaoTeste(), cfg.diasRegressivo(),
                    cfg.horasProdutivas(), cfg.papelInherited(), cfg.diasCodificacaoTesteInherited(),
                    cfg.diasRegressivoInherited(), cfg.horasProdutivasInherited(), cfg.papelSource(),
                    cfg.diasCodificacaoTesteSource(), cfg.diasRegressivoSource(), cfg.horasProdutivasSource(),
                    included, personFocus, focus, cap.baseHours(), cap.holidaysHours(), cap.absencesHours(),
                    cap.capacityHours()));
            if (included && CLASS_DEV.equals(cfg.papel())) dev += cap.capacityHours();
            if (included && CLASS_QA.equals(cfg.papel())) qa += cap.capacityHours();
        }
        Map<String, Double> byClass = new LinkedHashMap<>();
        byClass.put(CLASS_DEV, SprintPlanCalculator.round2(dev));
        byClass.put(CLASS_QA, SprintPlanCalculator.round2(qa));
        return new CapacityView(byClass, rows,
                SprintPlanCalculator.workingDays(plan.getStartDate(), plan.getEndDate(), holidays));
    }

    public Map<String, SprintPlanCalculator.Thermometer> thermometer(SprintPlan plan, CapacityView cap,
                                                                    Map<String, Double> allocated) {
        Double reserve = toDouble(plan.getReservePct());
        Map<String, SprintPlanCalculator.Thermometer> t = new LinkedHashMap<>();
        for (String cls : List.of(CLASS_DEV, CLASS_QA)) {
            t.put(cls, SprintPlanCalculator.thermometer(allocated.get(cls), cap.byClass().get(cls), reserve));
        }
        return t;
    }

    private static Double toDouble(BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }
}
