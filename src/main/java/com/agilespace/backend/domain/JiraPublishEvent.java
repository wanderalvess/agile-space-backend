package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Uma linha do log de um envio ao Jira: cada tentativa de passo, retentativa e conferência fica registrada. */
@Entity
@Table(name = "jira_publish_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JiraPublishEvent {

    public static final String SUBMITTED = "SUBMITTED";
    public static final String RETRY = "RETRY";
    public static final String STEP_DONE = "STEP_DONE";
    public static final String STEP_ERROR = "STEP_ERROR";
    public static final String JOB_DONE = "JOB_DONE";
    public static final String VERIFIED = "VERIFIED";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "job_id", nullable = false, length = 36)
    private String jobId;

    @Column(name = "step_position")
    private Integer stepPosition;

    @Column(length = 30)
    private String kind;

    @Column(nullable = false, length = 20)
    private String action;

    private Boolean ok;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "result_key", length = 60)
    private String resultKey;

    @Column(name = "user_id", length = 100)
    private String userId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
