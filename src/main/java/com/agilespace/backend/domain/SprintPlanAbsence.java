package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Ausência lançada no plano (V86). Fica só no plano: não altera squad_person_configs (decisão 7). */
@Entity
@Table(name = "sprint_plan_absences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SprintPlanAbsence {

    @Id
    private String id;

    @Column(name = "plan_id", nullable = false)
    private String planId;

    @Column(name = "jira_account_id", nullable = false)
    private String jiraAccountId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /** null = dia inteiro (horas produtivas da pessoa). */
    @Column(name = "hours_per_day", precision = 5, scale = 2)
    private BigDecimal hoursPerDay;

    @Column(name = "reason")
    private String reason;

    @Column(name = "created_by", nullable = false)
    private String createdBy;
}
