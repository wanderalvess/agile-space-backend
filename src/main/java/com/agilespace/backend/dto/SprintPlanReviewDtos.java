package com.agilespace.backend.dto;

import java.time.LocalDate;
import java.util.List;

/** Contrato da Revisão do Sprint Planner (E5): GET /api/squads/{s}/sprint-plans/{id}/review. Somente leitura. */
public final class SprintPlanReviewDtos {

    private SprintPlanReviewDtos() {}

    public record ReviewSprint(String name, LocalDate startDate, LocalDate endDate) {}

    /** role: DEV|QA; zone/state como na Distribuição. */
    public record ReviewPerson(String accountId, String name, String role, double capacityHours, double allocatedHours,
                               double freeHours, Double pct, String zone, String state, int subtaskCount,
                               int datedSubtaskCount) {}

    /** cls: DEV|QA. */
    public record ReviewSubtask(String key, String title, String typeName, String cls, String assigneeId,
                                String assigneeName, LocalDate startDate, LocalDate endDate,
                                java.math.BigDecimal estimateH) {}

    public record ReviewTask(String itemId, String parentKey, String title, java.math.BigDecimal devHours,
                             java.math.BigDecimal qaHours, List<ReviewSubtask> subtasks) {}

    /** severity: BLOCKING|WARNING. */
    public record Pending(String code, String severity, String message, String subtaskKey, String accountId,
                          String itemId) {}

    /** kind: SET|CHANGE|UNCHANGED. */
    public record GoalChange(String from, String to, String kind) {}

    /** field: assignee|sprint|plannedStart|plannedEnd; jiraFieldName null para assignee/sprint. */
    public record PreviewChange(String issueKey, String summary, String field, String jiraFieldName, String from,
                                String to, String kind) {}

    public record PreviewCounts(int set, int change, int unchanged, int issues) {}

    public record JiraPreview(String rulesNote, GoalChange sprintGoal, List<PreviewChange> changes,
                              PreviewCounts counts) {}

    public record ReviewView(String planId, String status, String syncedAt, boolean canEditPlan, ReviewSprint sprint,
                             String goal, boolean goalSavedInJira, List<ReviewPerson> people, List<ReviewTask> tasks,
                             List<Pending> pendings, int blockingCount, int warningCount, boolean canSend,
                             boolean writeRulesDefined, JiraPreview jiraPreview, String jiraSprintUrl) {}
}
