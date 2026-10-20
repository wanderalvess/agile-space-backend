package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/** Classe (DEV/QA/fora da conta/sem classificação) de um tipo de subtarefa do Jira, por squad e sprint de referência. */
@Entity
@Table(name = "squad_subtask_type_mapping")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SquadSubtaskTypeMapping {

    public static final String GLOBAL_SPRINT_KEY = "GLOBAL";

    @Id
    @Column(name = "id")
    private String id; // {squadId}|{sprintKey}|{typeId}

    @Column(name = "squad_id", nullable = false)
    private String squadId;

    @Column(name = "sprint_key", nullable = false)
    private String sprintKey;

    @Column(name = "type_id", nullable = false)
    private String typeId;

    @Column(name = "type_name", nullable = false)
    private String typeName;

    @Enumerated(EnumType.STRING)
    @Column(name = "class", nullable = false)
    private SubtaskClass subtaskClass;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false)
    private SubtaskMappingOrigin origin;

    @Column(name = "qa_by_title_exception", nullable = false)
    private boolean qaByTitleException;

    @Column(name = "default_hours", precision = 5, scale = 2)
    private BigDecimal defaultHours;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private String updatedAt;

    public static String idFor(String squadId, String sprintKey, String typeId) {
        return squadId + "|" + sprintKey + "|" + typeId;
    }
}
