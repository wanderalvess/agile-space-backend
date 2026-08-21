package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "work_items", uniqueConstraints = {
    @UniqueConstraint(name = "uk_work_item_squad_key", columnNames = {"squad_id", "jira_key"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkItem {

    @Id
    private String id;

    @Column(name = "squad_id", nullable = false)
    private String squadId;

    @Column(name = "sprint_id")
    private String sprintId;

    @Column(name = "jira_key", nullable = false)
    private String jiraKey;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "acceptance_criteria", columnDefinition = "TEXT")
    private String acceptanceCriteria;

    @Column(name = "type")
    private String type;

    @Column(name = "priority")
    private String priority;

    @Column(name = "assignee_name")
    private String assigneeName;

    @Column(name = "assignee_id")
    private String assigneeId;

    @Column(name = "dev_name")
    private String devName;

    @Column(name = "qa_name")
    private String qaName;

    @Column(name = "points_estimated")
    private Double pointsEstimated;

    @Column(name = "points_final")
    private Double pointsFinal;

    @Column(name = "estimate_sec")
    private Long estimateSec;

    @Column(name = "remaining_sec")
    private Long remainingSec;

    @Column(name = "logged_sec")
    private Long loggedSec;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "evidence_problem", columnDefinition = "TEXT")
    private String evidenceProblem;

    @Column(name = "evidence_solution", columnDefinition = "TEXT")
    private String evidenceSolution;

    @Column(name = "evidence_video_url", columnDefinition = "TEXT")
    private String evidenceVideoUrl;

    @Column(name = "decision")
    private String decision;

    @Column(name = "decision_feedback", columnDefinition = "TEXT")
    private String decisionFeedback;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "parent_key")
    private String parentKey;

    @Column(name = "parent_title")
    private String parentTitle;

    @Column(name = "target_start")
    private LocalDateTime targetStart;

    @Column(name = "target_end")
    private LocalDateTime targetEnd;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
