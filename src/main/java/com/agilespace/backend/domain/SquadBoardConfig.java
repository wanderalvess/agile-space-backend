package com.agilespace.backend.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Personalização do quadro Scrum da squad, compartilhada por todos que abrem o quadro. Cada parte é opcional:
 * nula = a squad não personalizou e vale o que o Jira traz. Os formatos são os da tela (lista de filtros, raias e
 * {id, min, max} por coluna).
 */
@Entity
@Table(name = "squad_board_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SquadBoardConfig {

    @Id
    @Column(name = "squad_id", length = 100)
    private String squadId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "quick_filters", columnDefinition = "jsonb")
    private JsonNode quickFilters;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "swimlanes", columnDefinition = "jsonb")
    private JsonNode swimlanes;

    /** queries | parents | assignees | epics | none */
    @Column(name = "swimlane_strategy", length = 20)
    private String swimlaneStrategy;

    /** [{id, min, max}] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "columns", columnDefinition = "jsonb")
    private JsonNode columns;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
