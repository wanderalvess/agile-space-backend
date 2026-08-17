package com.agilespace.backend.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "squads")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Squad {

    @Id
    private String id; // squadId

    @Column(name = "jira_project_key")
    private String jiraProjectKey;

    @Column(name = "sync_jql", columnDefinition = "TEXT")
    private String syncJql;

    @Column(name = "jira_domain")
    private String jiraDomain;

    @Column(name = "sprint_field_id")
    private String sprintFieldId;

    @Column(name = "active_sprint_id")
    private String activeSprintId;

    @Column(name = "schema_version")
    private Integer schemaVersion;

    @Column(name = "ranking_enabled")
    private Boolean rankingEnabled;

    @Column(name = "ranking_enabled_at")
    private String rankingEnabledAt;

    @Column(name = "last_sync_at")
    private String lastSyncAt;

    @Column(name = "last_full_reconcile_at")
    private String lastFullReconcileAt;

    @Column(name = "last_sync_status")
    private String lastSyncStatus;

    @Column(name = "reconcile_interval_hours")
    private Integer reconcileIntervalHours;

    @Column(name = "default_daily_capacity_hours")
    private Double defaultDailyCapacityHours;

    @Column(name = "updated_at")
    private String updatedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sprint_history", columnDefinition = "jsonb")
    private JsonNode sprintHistory;
}
