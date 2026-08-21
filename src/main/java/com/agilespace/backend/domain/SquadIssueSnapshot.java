package com.agilespace.backend.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "squad_issue_snapshots")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SquadIssueSnapshot {

    @Id
    @Column(name = "db_id")
    private String dbId; // PK = {squadId}_{jiraKey}

    @Column(name = "squad_id", nullable = false)
    private String squadId;

    @JsonProperty("jiraKey")
    @JsonAlias({"key", "jiraKey", "jira_key"})
    @Column(name = "jira_key", nullable = false)
    private String jiraKey; // ex: PROJ-123 — renomeado de 'key' para evitar colisão SQL

    @JsonProperty("key")
    public String getKey() {
        return jiraKey != null ? jiraKey : "";
    }

    public void setKey(String key) {
        if (key != null && !key.isBlank()) {
            this.jiraKey = key;
        }
    }

    @Column(name = "issue_type")
    private String type;

    @Column(name = "is_bug")
    private Boolean isBug;

    @Column(name = "status")
    private String status;

    @Column(name = "status_category")
    private String statusCategory;

    @Column(name = "sprint_id")
    private String sprintId;

    @Column(name = "sprint_name")
    private String sprintName;

    @Column(name = "assignee_id")
    private String assigneeId;

    @Column(name = "assignee_name")
    private String assigneeName;

    @Column(name = "estimate_sec")
    private Long estimateSec;

    @Column(name = "remaining_sec")
    private Long remainingSec;

    @Column(name = "logged_sec")
    private Long loggedSec;

    @Column(name = "stale_since_days")
    private Integer staleSinceDays;

    @Column(name = "due_date")
    private String dueDate;

    @Column(name = "parent_key")
    private String parentKey;

    @Column(name = "parent_title")
    private String parentTitle;

    @Column(name = "updated_at_jira")
    private String updatedAtJira;

    @Column(name = "synced_at")
    private String syncedAt;
}
