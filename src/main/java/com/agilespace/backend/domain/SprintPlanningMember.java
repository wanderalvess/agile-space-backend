package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "sprint_planning_members")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SprintPlanningMember {

    @Id
    private String id; // vem do cliente, não gerado no servidor

    @Column(name = "planning_id", nullable = false)
    private String planningId;

    @Column(nullable = false)
    private String name;

    private String role; // dev | qa

    private Integer focusFactor;

    private Integer daysOff;

    private Double hoursPerDay;

    @Column(name = "member_order")
    private Integer order;
}
