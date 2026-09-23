package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "sprint_plannings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SprintPlanning {

    @Id
    private String id; // UUID da sessão de planejamento

    private String title;

    private String createdBy;

    private String createdAt;

    private String updatedAt;

    @Embedded
    @Builder.Default
    private SprintPlanningSettings settings = SprintPlanningSettings.builder().build();

    // IDs de salas de poker já importadas pra este planejamento — lista simples, sem precisar
    // de tabela/entidade própria.
    @ElementCollection
    @CollectionTable(name = "sprint_planning_imported_poker_rooms", joinColumns = @JoinColumn(name = "planning_id"))
    @Column(name = "poker_room_id")
    @OrderColumn(name = "room_order")
    @Builder.Default
    private List<String> importedPokerRoomIds = new ArrayList<>();

    // tasks/members viviam como JSONB opaco (JsonNode) até esta migração; agora são tabelas
    // próprias (sprint_planning_tasks/_subtasks/_members, sem relação JPA — mesmo padrão de
    // ActionPlanTask/boardId) montadas manualmente pelo SprintPlanningService a cada
    // load/save, pra manter o contrato JSON idêntico ao que o frontend já espera
    // (data.tasks/data.members no mesmo objeto retornado por GET /sprint-plannings/{id}).
    @Transient
    private List<SprintPlanningTask> tasks;

    @Transient
    private List<SprintPlanningMember> members;
}
