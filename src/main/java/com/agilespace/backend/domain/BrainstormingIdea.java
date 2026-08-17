package com.agilespace.backend.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "brainstorming_ideas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BrainstormingIdea {

    @Id
    private String id; // UUID da ideia

    @Column(name = "board_id", nullable = false)
    private String boardId;

    @Column(columnDefinition = "TEXT")
    private String content;

    private String authorId;

    private String parentId;

    private String groupId;

    private String createdAt;

    private String color;

    // --- JSONB Fields mapping via Hibernate 6 JSON type ---
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "votes", columnDefinition = "jsonb")
    private JsonNode votes; // Array de UIDs de votos

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "position", columnDefinition = "jsonb")
    private JsonNode position; // Coordenadas {x, y}

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "qualifiers", columnDefinition = "jsonb")
    private JsonNode qualifiers; // {roi, effort}
}
