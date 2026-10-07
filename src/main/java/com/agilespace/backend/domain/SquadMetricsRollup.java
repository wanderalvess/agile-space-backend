package com.agilespace.backend.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "squad_metrics_rollup")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class SquadMetricsRollup {

    @Id
    @Column(name = "db_id")
    private String dbId; // PK = {squadId}_{sprintId} — 1 rollup por sprint, não por squad

    @Column(name = "squad_id", nullable = false)
    private String squadId;

    @Column(name = "sprint_id")
    private String sprintId;

    @Column(name = "sprint_name")
    private String sprintName;

    @Column(name = "total_issues")
    private Integer totalIssues;

    @Column(name = "done_issues")
    private Integer doneIssues;

    @Column(name = "in_progress_issues")
    private Integer inProgressIssues;

    @Column(name = "bug_issues")
    private Integer bugIssues;

    @Column(name = "stale_issues")
    private Integer staleIssues;

    @Column(name = "due_soon_issues")
    private Integer dueSoonIssues;

    @Column(name = "overdue_issues")
    private Integer overdueIssues;

    @Column(name = "estimate_total_sec")
    private Long estimateTotalSec;

    @Column(name = "remaining_total_sec")
    private Long remainingTotalSec;

    @Column(name = "logged_total_sec")
    private Long loggedTotalSec;

    @Column(name = "workdays_total")
    private Integer workdaysTotal;

    @Column(name = "workdays_remaining")
    private Integer workdaysRemaining;

    @Column(name = "computed_at")
    private String computedAt;

    // Campo de overflow para métricas extras sem schema fixo
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra_metrics", columnDefinition = "jsonb")
    private JsonNode extraMetrics;
}
