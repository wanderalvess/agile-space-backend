package com.agilespace.backend.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "health_check_boards")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HealthCheckBoard {

    @Id
    private String id; // UUID do quadro de saúde

    private String creatorId;

    @Column(length = 50)
    private String status; // collecting, finished

    private String createdAt;

    @Column(length = 50)
    private String scaleType; // traffic_light, numbers_5, emojis

    private String sprintName;

    private String team;

    // --- JSONB Fields mapping via Hibernate 6 JSON type ---
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dimensions", columnDefinition = "jsonb")
    private JsonNode dimensions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "participant_ids", columnDefinition = "jsonb")
    private JsonNode participantIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary", columnDefinition = "jsonb")
    private JsonNode summary;
}
