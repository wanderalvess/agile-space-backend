package com.agilespace.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Contrato da Distribuição do Sprint Planner (E4): /api/squads/{s}/sprint-plans/{id}/distribution. */
public final class SprintPlanDistributionDtos {

    private SprintPlanDistributionDtos() {}

    /** accountId vazio = atribuir a mim. Só liderança/ADMIN atribui a outra pessoa. */
    public record ClaimRequest(String subtaskKey, String accountId) {}

    /** yyyy-MM-dd; também aceita data-hora ISO (a parte da data é a que vale). */
    public record DatesRequest(String startDate, String endDate) {}

    public record HolidayEntry(LocalDate day, String label) {}

    public record SprintInfo(LocalDate startDate, LocalDate endDate, List<HolidayEntry> holidays) {}

    /**
     * cls: DEV | QA (distribuíveis); FORA | SEM nas subtarefas "Fora da conta" / "Sem classificação" (canClaim=false).
     * startsAfterKey/minStartDate: a cadeia Codificação, Code Review, QA.
     */
    public record DistSubtask(String key, String title, String typeId, String typeName, String cls, boolean codeReview,
                              BigDecimal estimateH, BigDecimal remainingH, BigDecimal countedH,
                              String assigneeId, String assigneeName, LocalDate startDate, LocalDate endDate,
                              String startsAfterKey, LocalDate minStartDate, boolean canClaim, boolean canEditDates,
                              String status) {}

    public record DistTask(String itemId, String parentKey, String title, BigDecimal devHours, BigDecimal qaHours,
                           List<DistSubtask> subtasks) {}

    /** zone: VERDE|AMARELO|VERMELHO|SEM_CAPACIDADE; state: DENTRO_DA_CAPACIDADE|COBERTO_PELA_RESERVA|ACIMA_DA_RESERVA|SEM_CAPACIDADE. */
    public record DistPerson(String accountId, String name, String role, double capacityHours, double allocatedHours,
                             double freeHours, Double pct, String zone, String state) {}

    public record DistWarning(String code, String message, String subtaskKey, String accountId) {}

    public record DistributionView(String planId, String status, String syncedAt, boolean canEditPlan, String myAccountId,
                                   SprintInfo sprint, List<DistTask> tasks, List<DistPerson> people,
                                   List<DistWarning> warnings) {}
}
