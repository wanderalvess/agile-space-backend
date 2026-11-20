package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;

/** Override por plano (V86): inclusão da pessoa e fator de foco individual (modo FOCO). */
@Entity
@Table(name = "sprint_plan_person")
@IdClass(SprintPlanPerson.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SprintPlanPerson {

    @Id
    @Column(name = "plan_id")
    private String planId;

    @Id
    @Column(name = "jira_account_id")
    private String jiraAccountId;

    @Column(name = "included", nullable = false)
    private boolean included;

    @Column(name = "focus_factor_pct", precision = 5, scale = 2)
    private BigDecimal focusFactorPct;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private String planId;
        private String jiraAccountId;
    }
}
