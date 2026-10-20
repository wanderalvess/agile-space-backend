package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDate;

/** Feriado lançado no plano (V86). Por plano nesta entrega (decisão 5); calendário reutilizável fica para depois. */
@Entity
@Table(name = "sprint_plan_holidays")
@IdClass(SprintPlanHoliday.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SprintPlanHoliday {

    @Id
    @Column(name = "plan_id")
    private String planId;

    @Id
    @Column(name = "day")
    private LocalDate day;

    @Column(name = "label", length = 120)
    private String label;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private String planId;
        private LocalDate day;
    }
}
