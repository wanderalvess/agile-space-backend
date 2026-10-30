package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/** Um envio "issue refinada → Jira": a pai renomeada, as sub-tarefas criadas e os comentários, em passos ordenados. */
@Entity
@Table(name = "jira_publish_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JiraPublishJob {

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String ERROR = "ERROR";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "squad_id", nullable = false, length = 100)
    private String squadId;

    @Column(name = "room_id", length = 100)
    private String roomId;

    /** Id da tarefa na fila do Poker (issuesQueue[].id). */
    @Column(name = "issue_id", length = 100)
    private String issueId;

    @Column(name = "parent_key", nullable = false, length = 60)
    private String parentKey;

    @Column(name = "parent_summary", columnDefinition = "TEXT")
    private String parentSummary;

    @Column(name = "requested_by", nullable = false, length = 100)
    private String requestedBy;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
}
