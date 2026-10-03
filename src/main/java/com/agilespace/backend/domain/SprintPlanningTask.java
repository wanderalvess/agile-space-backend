package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Entity
@Table(name = "sprint_planning_tasks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SprintPlanningTask {

    @Id
    private String id; // vem do cliente, não gerado no servidor

    @Column(name = "planning_id", nullable = false)
    private String planningId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(columnDefinition = "TEXT")
    private String link;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String status;

    @Column(name = "assignee_id")
    private String assigneeId;

    private String role; // dev | qa

    private Double hours;

    @Column(name = "start_date")
    private String startDate;

    @Column(name = "end_date")
    private String endDate;

    @Column(name = "task_order")
    private Integer order;

    // Montado manualmente pelo SprintPlanningService (própria tabela, sem relação JPA) —
    // ver comentário equivalente em SprintPlanning.tasks.
    @Transient
    private List<SprintPlanningSubtask> subtasks;
}
