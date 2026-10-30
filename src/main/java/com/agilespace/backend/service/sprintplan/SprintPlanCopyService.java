package com.agilespace.backend.service.sprintplan;

import com.agilespace.backend.domain.SprintPlan;
import com.agilespace.backend.domain.SprintPlanPerson;
import com.agilespace.backend.domain.SprintPlanReserveType;
import com.agilespace.backend.dto.SprintPlanDtos.PlanView;
import com.agilespace.backend.repository.SprintPlanPersonRepository;
import com.agilespace.backend.repository.SprintPlanRepository;
import com.agilespace.backend.repository.SprintPlanReserveTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Copiar da sprint anterior: reserva, tipos de reserva, fator de foco do time e inclusão/foco por pessoa. Não copia
 * ausências, feriados, itens nem atribuições. Capacidade por pessoa (papel/dias/horas) já herda pelo SquadCapacityService.
 */
@Service
@RequiredArgsConstructor
public class SprintPlanCopyService {

    private final SprintPlanRepository planRepository;
    private final SprintPlanPersonRepository personRepository;
    private final SprintPlanReserveTypeRepository reserveTypeRepository;
    private final SprintPlanService planService;

    @Transactional
    public PlanView copyFromPrevious(String squadId, String planId, String sourcePlanId, String userId) {
        SprintPlan target = planRepository.findById(planId).filter(p -> p.getSquadId().equals(squadId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plano não encontrado."));
        if (!SprintPlan.STATUS_RASCUNHO.equals(target.getStatus())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Só dá para copiar da sprint anterior enquanto o plano está em rascunho.");
        }
        SprintPlan source;
        if (sourcePlanId != null && !sourcePlanId.isBlank()) {
            source = planRepository.findById(sourcePlanId.trim()).filter(p -> p.getSquadId().equals(squadId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nenhum planejamento anterior"));
            if (source.getId().equals(target.getId())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Escolha um plano diferente deste.");
            }
        } else {
            source = planRepository.findBySquadIdOrderByStartDateDesc(squadId).stream()
                    .filter(p -> !p.getId().equals(target.getId()) && p.getEndDate().isBefore(target.getStartDate()))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nenhum planejamento anterior"));
        }

        target.setReserveMode(source.getReserveMode());
        target.setReservePct(source.getReservePct());
        target.setFocusFactorPct(source.getFocusFactorPct());
        target.setUpdatedAt(Instant.now());
        planRepository.saveAndFlush(target);

        reserveTypeRepository.deleteAllForPlan(planId);
        List<SprintPlanReserveType> types = reserveTypeRepository.findByPlanIdOrderByLabelAsc(source.getId()).stream()
                .map(t -> SprintPlanReserveType.builder().id(UUID.randomUUID().toString()).planId(planId)
                        .label(t.getLabel()).hoursPerDay(t.getHoursPerDay()).build()).toList();
        reserveTypeRepository.saveAllAndFlush(types);

        List<SprintPlanPerson> people = personRepository.findByPlanId(source.getId()).stream()
                .map(p -> SprintPlanPerson.builder().planId(planId).jiraAccountId(p.getJiraAccountId())
                        .included(p.isIncluded()).focusFactorPct(p.getFocusFactorPct()).build()).toList();
        personRepository.saveAllAndFlush(people);

        return planService.view(squadId, planId, true, userId);
    }
}
