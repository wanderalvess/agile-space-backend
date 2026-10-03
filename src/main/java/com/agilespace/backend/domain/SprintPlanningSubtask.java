package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "sprint_planning_subtasks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SprintPlanningSubtask {

    @Id
    private String id; // vem do cliente (crypto.randomUUID()), não gerado no servidor

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String name;

    private String status;

    @Column(columnDefinition = "TEXT")
    private String link;

    @Column(name = "assignee_id")
    private String assigneeId;

    private String role; // dev | qa

    private Double hours;

    @Column(name = "start_date")
    private String startDate;

    @Column(name = "end_date")
    private String endDate;

    @Column(name = "subtask_order")
    private Integer order;
}
