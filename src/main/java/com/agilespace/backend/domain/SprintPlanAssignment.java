package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Subtarefa do Jira distribuída no plano (V87). Endpoints de distribuição na E4.
 * Regra registrada para a E4 (decisão 3): se o DEV antecipa ou adia o fim, o QA acompanha nos dois sentidos.
 */
@Entity
@Table(name = "sprint_plan_assignments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SprintPlanAssignment {

    public static final String CLASS_DEV = "DEV";
    public static final String CLASS_QA = "QA";
    public static final String CLASS_FORA = "FORA";
    public static final String CLASS_SEM = "SEM";

    @Id
    private String id;

    @Column(name = "plan_id", nullable = false)
    private String planId;

    @Column(name = "item_id", nullable = false)
    private String itemId;

    @Column(name = "subtask_key", nullable = false, length = 64)
    private String subtaskKey;

    @Column(name = "jira_account_id")
    private String jiraAccountId;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    /** DEV | QA | FORA | SEM. */
    @Column(name = "class", nullable = false, length = 16)
    private String assignmentClass;

    @Column(name = "hours", precision = 7, scale = 2)
    private BigDecimal hours;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
