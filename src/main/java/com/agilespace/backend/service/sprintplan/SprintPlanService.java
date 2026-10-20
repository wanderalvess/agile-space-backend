package com.agilespace.backend.service.sprintplan;

import com.agilespace.backend.domain.*;
import com.agilespace.backend.dto.SprintPlanDtos.*;
import com.agilespace.backend.repository.*;
import com.agilespace.backend.service.SquadCapacityService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * CRUD e estado do plano do Sprint Planner (E1a). Autorização fica no controller (SquadAccessService); aqui só
 * regras: 404 plano de outra squad, 409 versão/estado/duplicado, 422 validação.
 */
@Service
@RequiredArgsConstructor
public class SprintPlanService {

    private static final List<String> VALID_PAPEIS = List.of("", "DEV", "QA");
    /** Status a partir dos quais a reserva do plano vira sugestão para o próximo. */
    private static final List<String> SUGGESTION_SOURCE_STATUS = List.of(SprintPlan.STATUS_REVISAO, SprintPlan.STATUS_ENVIADO);
    private static final Set<String> DELETABLE = Set.of(SprintPlan.STATUS_RASCUNHO, SprintPlan.STATUS_ESCOPO);

    private final SprintPlanRepository planRepository;
    private final SprintPlanPersonRepository personRepository;
    private final SprintPlanReserveTypeRepository reserveTypeRepository;
    private final SprintPlanAbsenceRepository absenceRepository;
    private final SprintPlanHolidayRepository holidayRepository;
    private final SprintPlanItemRepository itemRepository;
    private final SprintPlanAssignmentRepository assignmentRepository;
    private final SquadMemberRepository squadMemberRepository;
    private final SquadCapacityService squadCapacityService;
    private final SprintPlanCapacityService capacityService;

    // ---------------- Leitura ----------------

    @Transactional(readOnly = true)
    public List<PlanSummary> list(String squadId) {
        return planRepository.findBySquadIdOrderByStartDateDesc(squadId).stream().map(p -> {
            CapacityView cap = capacityService.compute(p);
            Map<String, Double> alloc = capacityService.allocated(p.getId());
            return new PlanSummary(p.getId(), p.getJiraSprintId(), p.getSprintName(), p.getStartDate(), p.getEndDate(),
                    p.getStatus(), new Totals(cap.byClass().get("DEV"), cap.byClass().get("QA"),
                    alloc.get("DEV"), alloc.get("QA")), p.getUpdatedAt());
        }).toList();
    }

    @Transactional(readOnly = true)
    public PlanView view(String squadId, String planId, boolean canEdit, String userId) {
        return toView(load(squadId, planId), canEdit, userId);
    }

    @Transactional(readOnly = true)
    public CapacityView capacity(String squadId, String planId) {
        return capacityService.compute(load(squadId, planId));
    }

    // ---------------- Plano ----------------

    @Transactional
    public PlanView create(String squadId, CreatePlanRequest req, String userId) {
        if (req == null) throw unprocessable("Corpo obrigatório.");
        String name = trimToNull(req.sprintName());
        if (name == null) throw unprocessable("Informe o nome da sprint.");
        validateDates(req.startDate(), req.endDate());
        String jiraSprintId = trimToNull(req.jiraSprintId());
        if (jiraSprintId != null && planRepository.existsBySquadIdAndJiraSprintId(squadId, jiraSprintId)) {
            throw conflict("Já existe um plano desta squad para essa sprint.");
        }
        Instant now = Instant.now();
        SprintPlan plan = SprintPlan.builder()
                .id(UUID.randomUUID().toString())
                .squadId(squadId)
                .jiraSprintId(jiraSprintId)
                .sprintName(name)
                .startDate(req.startDate())
                .endDate(req.endDate())
                .status(SprintPlan.STATUS_RASCUNHO)
                .goal(trimToNull(req.goal()))
                .reserveMode(SprintPlan.RESERVE_HORAS)
                .reservePct(suggestedReservePct(squadId))
                .createdBy(userId)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            plan = planRepository.saveAndFlush(plan);
        } catch (DataIntegrityViolationException e) {
            throw conflict("Já existe um plano desta squad para essa sprint.");
        }
        return toView(plan, true, userId);
    }

    /** Reserva sugerida = a do último plano da squad que chegou à revisão. */
    BigDecimal suggestedReservePct(String squadId) {
        return planRepository.findFirstBySquadIdAndStatusInOrderByStartDateDesc(squadId, SUGGESTION_SOURCE_STATUS)
                .map(SprintPlan::getReservePct).orElse(null);
    }

    @Transactional
    public PlanView patch(String squadId, String planId, PatchPlanRequest req, String userId) {
        if (req == null) throw unprocessable("Corpo obrigatório.");
        SprintPlan plan = load(squadId, planId);
        requireVersion(plan, req.version());
        requireEditable(plan);
        if (req.sprintName() != null) {
            String name = trimToNull(req.sprintName());
            if (name == null) throw unprocessable("Informe o nome da sprint.");
            plan.setSprintName(name);
        }
        LocalDate start = req.startDate() != null ? req.startDate() : plan.getStartDate();
        LocalDate end = req.endDate() != null ? req.endDate() : plan.getEndDate();
        validateDates(start, end);
        plan.setStartDate(start);
        plan.setEndDate(end);
        if (req.goal() != null) plan.setGoal(trimToNull(req.goal()));
        if (req.reserveMode() != null) {
            if (!SprintPlan.RESERVE_HORAS.equals(req.reserveMode()) && !SprintPlan.RESERVE_FOCO.equals(req.reserveMode())) {
                throw unprocessable("Modo de reserva inválido: use HORAS ou FOCO.");
            }
            plan.setReserveMode(req.reserveMode());
        }
        if (req.reservePct() != null) {
            plan.setReservePct(validatePct(req.reservePct(), true, "Reserva"));
        }
        if (req.focusFactorPct() != null) {
            plan.setFocusFactorPct(validatePct(req.focusFactorPct(), false, "Fator de foco"));
        }
        plan.setUpdatedAt(Instant.now());
        plan = planRepository.saveAndFlush(plan);
        return toView(plan, true, userId);
    }

    /**
     * Avança ou volta uma etapa (RASCUNHO ↔ ESCOPO ↔ DISTRIBUICAO ↔ REVISAO). ENVIADO só pela escrita no Jira (E6): 501.
     */
    @Transactional
    public PlanView changeStatus(String squadId, String planId, StatusRequest req, String userId) {
        if (req == null || req.to() == null) throw unprocessable("Informe o status de destino.");
        SprintPlan plan = load(squadId, planId);
        requireVersion(plan, req.version());
        int to = SprintPlan.STATUS_ORDER.indexOf(req.to());
        if (to < 0) throw unprocessable("Status inválido.");
        if (SprintPlan.STATUS_ENVIADO.equals(req.to())) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "O envio ao Jira ainda não está disponível.");
        }
        requireEditable(plan);
        int from = SprintPlan.STATUS_ORDER.indexOf(plan.getStatus());
        if (Math.abs(to - from) != 1) {
            throw conflict("Transição inválida: de " + plan.getStatus() + " só dá para avançar ou voltar uma etapa.");
        }
        plan.setStatus(req.to());
        plan.setUpdatedAt(Instant.now());
        plan = planRepository.saveAndFlush(plan);
        return toView(plan, true, userId);
    }

    @Transactional
    public void delete(String squadId, String planId) {
        SprintPlan plan = load(squadId, planId);
        if (!DELETABLE.contains(plan.getStatus())) {
            throw conflict("Só dá para excluir plano em rascunho ou escopo.");
        }
        planRepository.delete(plan); // filhos saem pelo ON DELETE CASCADE
    }

    // ---------------- Capacidade por pessoa ----------------

    /**
     * Papel/dias/horas vão para squad_person_configs na chave da sprint do plano (o mesmo dado do roster, sem
     * duplicar); inclusão e foco ficam em sprint_plan_person.
     */
    @Transactional
    public CapacityRow updatePerson(String squadId, String planId, String accountId, CapacityUpdateRequest req) {
        if (req == null) throw unprocessable("Corpo obrigatório.");
        SprintPlan plan = load(squadId, planId);
        requireEditable(plan);
        requireMember(squadId, accountId);
        if (req.papel() != null && !VALID_PAPEIS.contains(req.papel())) throw unprocessable("Papel inválido: use DEV, QA ou vazio.");
        if (req.diasCod() != null && (req.diasCod() < 0 || req.diasCod() > 31)) throw unprocessable("Dias de Codificação/Teste: use um valor entre 0 e 31.");
        if (req.diasReg() != null && (req.diasReg() < 0 || req.diasReg() > 31)) throw unprocessable("Dias de Regressivo: use um valor entre 0 e 31.");
        if (req.horasProdutivas() != null && (req.horasProdutivas() < 0 || req.horasProdutivas() > 24)) {
            throw unprocessable("Horas produtivas/dia: use um valor entre 0 e 24.");
        }
        BigDecimal focus = req.focusPct() == null ? null : validatePct(req.focusPct(), false, "Fator de foco");

        if (req.papel() != null || req.diasCod() != null || req.diasReg() != null || req.horasProdutivas() != null) {
            SquadPersonConfig updates = new SquadPersonConfig();
            updates.setPapel(req.papel());
            updates.setDiasCodificacaoTeste(req.diasCod());
            updates.setDiasRegressivo(req.diasReg());
            updates.setHorasProdutivas(req.horasProdutivas());
            try {
                squadCapacityService.save(squadId, accountId, plan.sprintKey(), updates);
            } catch (ResponseStatusException e) {
                throw unprocessable(e.getReason());
            }
        }
        if (req.included() != null || focus != null || Boolean.TRUE.equals(req.clearFocus())) {
            SprintPlanPerson p = personRepository.findById(new SprintPlanPerson.Key(planId, accountId))
                    .orElseGet(() -> SprintPlanPerson.builder().planId(planId).jiraAccountId(accountId).included(true).build());
            if (req.included() != null) p.setIncluded(req.included());
            if (Boolean.TRUE.equals(req.clearFocus())) p.setFocusFactorPct(null);
            else if (focus != null) p.setFocusFactorPct(focus);
            personRepository.saveAndFlush(p);
        }
        return capacityService.compute(plan).people().stream()
                .filter(r -> r.accountId().equals(accountId)).findFirst()
                .orElseThrow(() -> unprocessable("Pessoa não encontrada no time da squad."));
    }

    // ---------------- Ausências e feriados ----------------

    @Transactional
    public AbsenceView addAbsence(String squadId, String planId, AbsenceRequest req, String userId) {
        if (req == null) throw unprocessable("Corpo obrigatório.");
        SprintPlan plan = load(squadId, planId);
        requireEditable(plan);
        String acc = trimToNull(req.accountId());
        if (acc == null) throw unprocessable("Informe a pessoa.");
        requireMember(squadId, acc);
        LocalDate start = req.startDate();
        LocalDate end = req.endDate() != null ? req.endDate() : start;
        if (start == null) throw unprocessable("Informe a data da ausência.");
        if (end.isBefore(start)) throw unprocessable("A data final não pode ser antes da inicial.");
        requireInsideSprint(plan, start, "A ausência precisa estar dentro da sprint.");
        requireInsideSprint(plan, end, "A ausência precisa estar dentro da sprint.");
        if (req.hoursPerDay() != null && (req.hoursPerDay().signum() <= 0 || req.hoursPerDay().compareTo(BigDecimal.valueOf(24)) > 0)) {
            throw unprocessable("Horas por dia da ausência: use um valor maior que 0 e até 24 (vazio = dia inteiro).");
        }
        String reason = trimToNull(req.reason());
        if (reason != null && reason.length() > 255) throw unprocessable("Motivo muito longo (máx. 255).");
        SprintPlanAbsence a = absenceRepository.saveAndFlush(SprintPlanAbsence.builder()
                .id(UUID.randomUUID().toString()).planId(planId).jiraAccountId(acc)
                .startDate(start).endDate(end).hoursPerDay(req.hoursPerDay()).reason(reason).createdBy(userId).build());
        return toAbsenceView(a);
    }

    @Transactional
    public void deleteAbsence(String squadId, String planId, String absenceId) {
        SprintPlan plan = load(squadId, planId);
        requireEditable(plan);
        SprintPlanAbsence a = absenceRepository.findById(absenceId)
                .filter(x -> x.getPlanId().equals(planId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ausência não encontrada."));
        absenceRepository.delete(a);
    }

    /** Substitui a lista de feriados do plano. */
    @Transactional
    public List<HolidayDto> replaceHolidays(String squadId, String planId, List<HolidayDto> holidays) {
        SprintPlan plan = load(squadId, planId);
        requireEditable(plan);
        List<HolidayDto> in = holidays == null ? List.of() : holidays;
        Set<LocalDate> seen = new HashSet<>();
        for (HolidayDto h : in) {
            if (h == null || h.day() == null) throw unprocessable("Informe o dia de cada feriado.");
            requireInsideSprint(plan, h.day(), "O feriado precisa estar dentro da sprint.");
            if (!seen.add(h.day())) throw unprocessable("Feriado repetido: " + h.day() + ".");
            if (h.label() != null && h.label().length() > 120) throw unprocessable("Nome do feriado muito longo (máx. 120).");
        }
        holidayRepository.deleteAllForPlan(planId);
        holidayRepository.saveAllAndFlush(in.stream().map(h -> SprintPlanHoliday.builder()
                .planId(planId).day(h.day()).label(trimToNull(h.label())).build()).toList());
        return holidayRepository.findByPlanIdOrderByDayAsc(planId).stream()
                .map(h -> new HolidayDto(h.getDay(), h.getLabel())).toList();
    }

    /** Substitui os tipos de reserva ("fora da conta") do plano. */
    @Transactional
    public List<ReserveTypeView> replaceReserveTypes(String squadId, String planId, List<ReserveTypeRequest> types) {
        SprintPlan plan = load(squadId, planId);
        requireEditable(plan);
        List<ReserveTypeRequest> in = types == null ? List.of() : types;
        for (ReserveTypeRequest t : in) {
            String label = t == null ? null : trimToNull(t.label());
            if (label == null) throw unprocessable("Informe o nome de cada tipo de reserva.");
            if (label.length() > 120) throw unprocessable("Nome do tipo de reserva muito longo (máx. 120).");
            if (t.hoursPerDay() != null && (t.hoursPerDay().signum() < 0 || t.hoursPerDay().compareTo(BigDecimal.valueOf(24)) > 0)) {
                throw unprocessable("Horas por dia do tipo de reserva: use um valor entre 0 e 24.");
            }
        }
        reserveTypeRepository.deleteAllForPlan(planId);
        reserveTypeRepository.saveAllAndFlush(in.stream().map(t -> SprintPlanReserveType.builder()
                .id(UUID.randomUUID().toString()).planId(planId).label(t.label().trim()).hoursPerDay(t.hoursPerDay()).build()).toList());
        return reserveTypeRepository.findByPlanIdOrderByLabelAsc(planId).stream()
                .map(t -> new ReserveTypeView(t.getId(), t.getLabel(), t.getHoursPerDay())).toList();
    }

    // ---------------- Apoio ----------------

    SprintPlan load(String squadId, String planId) {
        return planRepository.findById(planId)
                .filter(p -> p.getSquadId().equals(squadId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plano não encontrado."));
    }

    PlanView toView(SprintPlan p, boolean canEdit, String userId) {
        CapacityView cap = capacityService.compute(p);
        Map<String, Double> alloc = capacityService.allocated(p.getId());
        List<ReserveTypeView> types = reserveTypeRepository.findByPlanIdOrderByLabelAsc(p.getId()).stream()
                .map(t -> new ReserveTypeView(t.getId(), t.getLabel(), t.getHoursPerDay())).toList();
        List<AbsenceView> absences = absenceRepository.findByPlanIdOrderByStartDateAsc(p.getId()).stream()
                .map(SprintPlanService::toAbsenceView).toList();
        List<HolidayDto> holidays = holidayRepository.findByPlanIdOrderByDayAsc(p.getId()).stream()
                .map(h -> new HolidayDto(h.getDay(), h.getLabel())).toList();
        List<ItemView> items = itemRepository.findByPlanIdOrderBySortOrderAsc(p.getId()).stream()
                .map(i -> new ItemView(i.getId(), i.getParentKey(), i.getTitleSnapshot(), i.getTitleDevHours(),
                        i.getTitleQaHours(), i.getSortOrder(), i.isCarryOver())).toList();
        List<AssignmentView> assignments = assignmentRepository.findByPlanId(p.getId()).stream()
                .map(a -> new AssignmentView(a.getId(), a.getItemId(), a.getSubtaskKey(), a.getJiraAccountId(),
                        a.getStartDate(), a.getEndDate(), a.getAssignmentClass(), a.getHours(), a.getVersion())).toList();
        // Mapear tipos de subtarefa: qualquer liderança da squad (inclui AM), decisão 4 — o mesmo critério de editar.
        Permissions perms = new Permissions(canEdit, canEdit, myAccountId(p.getSquadId(), userId));
        return new PlanView(p.getId(), p.getSquadId(), p.getVersion(), p.getStatus(),
                new SprintView(p.getJiraSprintId(), p.getSprintName(), p.getStartDate(), p.getEndDate(), p.sprintKey()),
                p.getGoal(), new ReserveView(p.getReserveMode(), p.getReservePct(), p.getFocusFactorPct(), types),
                null, cap, absences, holidays, items, assignments,
                capacityService.thermometer(p, cap, alloc), perms, p.getUpdatedAt());
    }

    private String myAccountId(String squadId, String userId) {
        if (userId == null) return null;
        return squadMemberRepository.findByClaimedByUid(userId).stream()
                .filter(m -> squadId.equals(m.getSquadId()))
                .map(SquadMember::getJiraAccountId).findFirst().orElse(null);
    }

    private static AbsenceView toAbsenceView(SprintPlanAbsence a) {
        return new AbsenceView(a.getId(), a.getJiraAccountId(), a.getStartDate(), a.getEndDate(), a.getHoursPerDay(), a.getReason());
    }

    private void requireMember(String squadId, String accountId) {
        if (squadMemberRepository.findBySquadIdAndJiraAccountId(squadId, accountId).isEmpty()) {
            throw unprocessable("Pessoa não encontrada no time da squad.");
        }
    }

    private static void requireVersion(SprintPlan plan, Long version) {
        if (version == null) throw unprocessable("Informe a versão do plano.");
        if (!version.equals(plan.getVersion())) {
            throw conflict("O plano foi alterado por outra pessoa. Recarregue e tente de novo.");
        }
    }

    private static void requireEditable(SprintPlan plan) {
        if (SprintPlan.STATUS_ENVIADO.equals(plan.getStatus())) {
            throw conflict("O plano já foi enviado ao Jira e não pode mais ser alterado.");
        }
    }

    private static void requireInsideSprint(SprintPlan plan, LocalDate d, String msg) {
        if (d.isBefore(plan.getStartDate()) || d.isAfter(plan.getEndDate())) throw unprocessable(msg);
    }

    private static void validateDates(LocalDate start, LocalDate end) {
        if (start == null || end == null) throw unprocessable("Informe as datas de início e fim da sprint.");
        if (end.isBefore(start)) throw unprocessable("A data de fim não pode ser antes do início.");
    }

    /** Reserva: 0–100. Foco: maior que 0 e até 100. Máximo 2 casas. */
    private static BigDecimal validatePct(BigDecimal v, boolean allowZero, String label) {
        boolean low = allowZero ? v.signum() < 0 : v.signum() <= 0;
        if (low || v.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw unprocessable(label + ": use um valor " + (allowZero ? "entre 0" : "maior que 0") + " e até 100.");
        }
        if (v.stripTrailingZeros().scale() > 2) throw unprocessable(label + ": use no máximo 2 casas decimais.");
        return v;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    static ResponseStatusException unprocessable(String msg) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
    }

    static ResponseStatusException conflict(String msg) {
        return new ResponseStatusException(HttpStatus.CONFLICT, msg);
    }
}
