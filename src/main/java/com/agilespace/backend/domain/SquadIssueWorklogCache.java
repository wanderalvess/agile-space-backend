package com.agilespace.backend.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "squad_issue_worklog_cache")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SquadIssueWorklogCache {

    @Id
    @Column(name = "db_id")
    private String dbId; // PK = {squadId}_{jiraKey}

    @Column(name = "squad_id", nullable = false)
    private String squadId;

    @Column(name = "jira_key", nullable = false)
    private String jiraKey;

    @Column(name = "sprint_id")
    private String sprintId;

    @Column(name = "updated_at_jira")
    private String updatedAtJira;

    @Column(name = "synced_at")
    private String syncedAt;

    // Map<assigneeId, hoursLogged>
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "worklog_by_author", columnDefinition = "jsonb")
    private JsonNode worklogByAuthor;

    // Map<assigneeId, displayName>
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "worklog_author_names", columnDefinition = "jsonb")
    private JsonNode worklogAuthorNames;
}
