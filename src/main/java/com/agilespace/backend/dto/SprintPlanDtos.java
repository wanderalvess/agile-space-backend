package com.agilespace.backend.dto;

import com.agilespace.backend.service.sprintplan.SprintPlanCalculator;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Contrato REST do Sprint Planner (plano técnico, seção 4). */
public final class SprintPlanDtos {

    private SprintPlanDtos() {}

    // ---- Requisições ----

    public record CreatePlanRequest(String jiraSprintId, String sprintName, LocalDate startDate, LocalDate endDate,
                                    String goal) {}

    public record PatchPlanRequest(Long version, String sprintName, LocalDate startDate, LocalDate endDate,
                                   String goal, String reserveMode, BigDecimal reservePct, BigDecimal focusFactorPct) {}

    public record StatusRequest(Long version, String to) {}

    public record VersionRequest(Long version) {}

    public record CapacityUpdateRequest(String papel, Integer diasCod, Integer diasReg, Double horasProdutivas,
                                        Boolean included, BigDecimal focusPct, Boolean clearFocus) {}

    public record AbsenceRequest(String accountId, LocalDate startDate, LocalDate endDate, BigDecimal hoursPerDay,
                                 String reason) {}

    public record HolidayDto(LocalDate day, String label) {}

    public record ReserveTypeRequest(String label, BigDecimal hoursPerDay) {}

    // ---- Respostas ----

    public record PlanSummary(String id, String jiraSprintId, String sprintName, LocalDate startDate, LocalDate endDate,
                              String status, Totals totals, Instant updatedAt) {}

    public record Totals(double capDev, double capQa, double planDev, double planQa) {}

    public record CapacityRow(String accountId, String name, String papel, int diasCod, int diasReg,
                              double horasProdutivas,
                              boolean papelInherited, boolean diasCodInherited, boolean diasRegInherited,
                              boolean horasProdutivasInherited,
                              String papelSource, String diasCodSource, String diasRegSource, String horasProdutivasSource,
                              boolean included, BigDecimal personFocusPct, Double focusPct,
                              double baseHours, double holidaysHours, double absencesHours, double capacityHours) {}

    public record CapacityView(Map<String, Double> byClass, List<CapacityRow> people, int workingDays) {}

    public record AbsenceView(String id, String accountId, LocalDate startDate, LocalDate endDate,
                              BigDecimal hoursPerDay, String reason) {}

    public record ReserveTypeView(String id, String label, BigDecimal hoursPerDay) {}

    public record ReserveView(String mode, BigDecimal pct, BigDecimal focusPct, List<ReserveTypeView> types) {}

    public record SprintView(String jiraSprintId, String name, LocalDate startDate, LocalDate endDate, String sprintKey) {}

    // ---- Escopo (E3) ----

    public record AddItemsRequest(String parentKey, List<String> parentKeys) {}

    public record ReorderItemsRequest(List<String> orderedItemIds) {}

    /** Subtarefa da pai como entra na conta: classe pelo tipo do Jira; só DEV/QA somam. */
    public record ScopeSubtaskView(String key, String title, String typeId, String typeName, String cls,
                                   BigDecimal estimateH, BigDecimal remainingH, BigDecimal countedH,
                                   boolean defaultHoursApplied, String assigneeId, String assigneeName) {}

    /**
     * Item do escopo. {@code devHours}/{@code qaHours} são o que CONTA no total (HORAS_ITEM); {@code subtaskDevH}/
     * {@code subtaskQaH} são a soma das subtarefas da classe SEM Code Review (estimado; restante se carry-over), só para
     * alerta. {@code codeReviewDevH}/{@code codeReviewQaH} são as horas de Code Review incluídas em devHours/qaHours;
     * {@code codeReviewDevAssumed} = não há subtarefa de Code Review DEV e entrou a hora padrão.
     */
    public record ItemView(String id, String parentKey, String title, BigDecimal titleDevHours,
                           BigDecimal titleQaHours, int sortOrder, boolean carryOver,
                           BigDecimal devHours, BigDecimal qaHours, BigDecimal subtaskDevH, BigDecimal subtaskQaH,
                           BigDecimal subtaskDevEstimateH, BigDecimal subtaskDevRemainingH,
                           BigDecimal subtaskQaEstimateH, BigDecimal subtaskQaRemainingH,
                           boolean divergenceDev, boolean divergenceQa, boolean divergent, boolean noEstimate,
                           boolean inBacklog, int unclassifiedCount, BigDecimal unclassifiedHours,
                           List<ScopeSubtaskView> subtasks,
                           BigDecimal codeReviewDevH, BigDecimal codeReviewQaH, boolean codeReviewDevAssumed) {}

    public record UnclassifiedTypeView(String typeId, String typeName, int count, BigDecimal hours) {}

    public record ScopeTotals(int itemCount, Map<String, Double> allocated, int carryOverCount,
                              double carryOverDevHours, double carryOverQaHours, int divergentCount,
                              int noEstimateCount, int unclassifiedCount, double unclassifiedHours,
                              List<UnclassifiedTypeView> unclassifiedTypes) {}

    public record ScopeView(String planId, String status, String syncedAt, List<ItemView> items, ScopeTotals totals,
                            Map<String, SprintPlanCalculator.Thermometer> thermometer, boolean canEditPlan) {}

    public record AssignmentView(String id, String itemId, String subtaskKey, String accountId, LocalDate startDate,
                                 LocalDate endDate, String cls, BigDecimal hours, Long version) {}

    public record Permissions(boolean canEditPlan, boolean canEditTypes, String myAccountId) {}

    public record PlanView(String id, String squadId, Long version, String status, SprintView sprint, String goal,
                           ReserveView reserve, String syncedAt, CapacityView capacity,
                           List<AbsenceView> absences, List<HolidayDto> holidays,
                           List<ItemView> items, ScopeTotals scope, List<AssignmentView> assignments,
                           Map<String, SprintPlanCalculator.Thermometer> thermometer, Permissions permissions,
                           boolean goalSavedInJira, Instant updatedAt) {}
}
