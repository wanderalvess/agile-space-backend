package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "showcase_tasks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShowcaseTask {

    @Id
    private String id;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "task_key")
    private String key;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "acceptance_criteria", columnDefinition = "TEXT")
    private String acceptanceCriteria;

    @Column(name = "task_type")
    private String type;

    private String status;

    private String priority;

    private Double points;

    @Column(name = "card_kind")
    private String cardKind;

    @Column(name = "chart_type")
    private String chartType;

    @Column(name = "chart_title")
    private String chartTitle;

    @Column(name = "chart_display")
    private String chartDisplay;

    private String assignee;

    @Column(columnDefinition = "TEXT")
    private String url;

    private String decision;

    @Column(name = "preparation_status")
    private String preparationStatus;

    @Column(columnDefinition = "TEXT")
    private String feedback;

    private String project;

    @Column(name = "version_suporte")
    private String versionSuporte;

    @Column(name = "version_master")
    private String versionMaster;

    @Column(name = "version_release")
    private String versionRelease;

    @Column(name = "version_develop")
    private String versionDevelop;

    @Column(name = "approved_at")
    private String approvedAt;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_by_name")
    private String decidedByName;

    @Column(name = "decided_at")
    private String decidedAt;

    @Embedded
    @Builder.Default
    private ShowcaseEvidence evidence = ShowcaseEvidence.builder().build();

    @ElementCollection
    @CollectionTable(name = "showcase_task_metrics", joinColumns = @JoinColumn(name = "task_id"))
    @OrderColumn(name = "metric_order")
    @Builder.Default
    private List<ShowcaseImpactMetric> metrics = new ArrayList<>();

    @Column(name = "task_order")
    private Integer order;
}
