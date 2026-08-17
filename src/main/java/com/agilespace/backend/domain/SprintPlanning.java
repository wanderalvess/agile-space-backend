package com.agilespace.backend.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    // --- JSONB Fields mapping via Hibernate 6 JSON type ---
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tasks", columnDefinition = "jsonb")
    private JsonNode tasks;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "members", columnDefinition = "jsonb")
    private JsonNode members;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings", columnDefinition = "jsonb")
    private JsonNode settings;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "imported_poker_room_ids", columnDefinition = "jsonb")
    private JsonNode importedPokerRoomIds;
}
