package com.agilespace.backend.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "brainstorming_boards")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BrainstormingBoard {

    @Id
    private String id; // UUID do quadro

    private String creatorId;

    private String title;

    private String team;

    private String createdAt;

    @Column(length = 50)
    private String phase; // ideation, diagram, grouping, prioritization, actions

    // --- JSONB Fields mapping via Hibernate 6 JSON type ---
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings", columnDefinition = "jsonb")
    private JsonNode settings;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "participant_ids", columnDefinition = "jsonb")
    private JsonNode participantIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "timer", columnDefinition = "jsonb")
    private JsonNode timer;
}
