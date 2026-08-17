package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "squad_member_metrics")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SquadMemberMetric {

    @Id
    @Column(name = "db_id")
    private String dbId; // PK = {squadId}_{assigneeId}

    @Column(name = "squad_id", nullable = false)
    private String squadId;

    @Column(name = "sprint_id")
    private String sprintId;

    @Column(name = "assignee_id", nullable = false)
    private String assigneeId;

    @Column(name = "assignee_name")
    private String assigneeName;

    @Column(name = "issues_in_progress")
    private Integer issuesInProgress;

    @Column(name = "issues_completed")
    private Integer issuesCompleted;

    @Column(name = "hours_logged")
    private Double hoursLogged;

    @Column(name = "capacity_hours")
    private Double capacityHours;

    @Column(name = "utilization_pct")
    private Double utilizationPct;

    @Column(name = "computed_at")
    private String computedAt;
}
