package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "action_plan_tasks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActionPlanTask {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID boardId;

    @Column(columnDefinition = "TEXT")
    private String what;

    @Column(columnDefinition = "TEXT")
    private String why;

    @Column(name = "where_location", columnDefinition = "TEXT")
    private String where; // Onde - mapeado para where_location no Postgres

    @Column(name = "when_field", columnDefinition = "TEXT")
    private String when; // Quando - mapeado para when_field no Postgres

    private String who;

    @Column(columnDefinition = "TEXT")
    private String how;

    private String howMuch;

    @Column(length = 50)
    private String status; // todo, doing, done, blocked

    private String authorId;

    @Column(name = "task_order")
    private Integer order; // Ordem de exibição - mapeado para task_order no Postgres

    /** Item de origem da ação (V78): id do cartão da retro, dimensão do radar, ideia do brainstorming. Imutável. */
    @Column(name = "source_item_id", length = 80)
    private String sourceItemId;

    @Column(name = "source_label")
    private String sourceLabel;

    /** Prazo como data de verdade (V78); "when" segue como texto livre. */
    @Column(name = "due_date")
    private LocalDate dueDate;

    /** Só na edição: true limpa o prazo (dueDate nulo = "não mexer"). Não persistido. */
    @Transient
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
    private Boolean clearDueDate;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
