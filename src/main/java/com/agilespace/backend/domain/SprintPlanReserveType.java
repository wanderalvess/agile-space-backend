package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/** Tipo de reserva "fora da conta" (reunião, suporte...), informativo (V86). */
@Entity
@Table(name = "sprint_plan_reserve_types")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SprintPlanReserveType {

    @Id
    private String id;

    @Column(name = "plan_id", nullable = false)
    private String planId;

    @Column(name = "label", nullable = false, length = 120)
    private String label;

    @Column(name = "hours_per_day", precision = 5, scale = 2)
    private BigDecimal hoursPerDay;
}
