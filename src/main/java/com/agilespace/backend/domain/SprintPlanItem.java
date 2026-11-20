package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/** Pai do Jira incluída no escopo do plano (V87). Endpoints de escopo na E3. */
@Entity
@Table(name = "sprint_plan_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SprintPlanItem {

    @Id
    private String id;

    @Column(name = "plan_id", nullable = false)
    private String planId;

    @Column(name = "parent_key", nullable = false, length = 64)
    private String parentKey;

    @Column(name = "title_snapshot", columnDefinition = "TEXT")
    private String titleSnapshot;

    @Column(name = "title_dev_hours", precision = 7, scale = 2)
    private BigDecimal titleDevHours;

    @Column(name = "title_qa_hours", precision = 7, scale = 2)
    private BigDecimal titleQaHours;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "carry_over", nullable = false)
    private boolean carryOver;

    @Column(name = "added_by", nullable = false)
    private String addedBy;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;
}
