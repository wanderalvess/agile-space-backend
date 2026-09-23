package com.agilespace.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SprintPlanningSettings {

    @Column(name = "sprint_start_date")
    private String sprintStartDate;

    @Column(name = "working_days")
    @Builder.Default
    private Integer workingDays = 10;

    @Column(name = "focus_factor")
    @Builder.Default
    private Integer focusFactor = 70;

    @Column(name = "dev_count")
    @Builder.Default
    private Integer devCount = 0;

    @Column(name = "dev_absences")
    @Builder.Default
    private Integer devAbsences = 0;

    @Column(name = "qa_count")
    @Builder.Default
    private Integer qaCount = 0;

    @Column(name = "qa_absences")
    @Builder.Default
    private Integer qaAbsences = 0;

    @Column(name = "is_detailed_mode")
    @Builder.Default
    private Boolean isDetailedMode = false;

    @Column(name = "default_dev_hours_per_day")
    @Builder.Default
    private Double defaultDevHoursPerDay = 8.0;

    @Column(name = "default_qa_hours_per_day")
    @Builder.Default
    private Double defaultQaHoursPerDay = 8.0;

    // Nunca setado pelo frontend atual (grep em src/ não encontra nenhum ponto que escreva
    // isReadyForPoker) — TopicQueue.tsx chama listReadyForPoker, que já dependia desse campo
    // antes desta migração via JSONB (settings->>'isReadyForPoker'). Preservado pra não quebrar
    // SprintPlanningRepository.findReadyForPoker; a feature em si parece nunca ter sido concluída
    // no frontend novo, mas consertar isso é decisão de produto, não desta migração de schema.
    @Column(name = "ready_for_poker")
    @Builder.Default
    private Boolean isReadyForPoker = false;
}
