package com.agilespace.backend.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
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
@JsonIgnoreProperties(value = {"lifecycleStatus", "finishedAt", "presentationStage", "respondedCount", "completedCount",
        "participantsCount"}, allowGetters = true)
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

    /** Instante do encerramento (ISO-8601). Nulo enquanto aberto e nos radares encerrados antes da V79. Só leitura no JSON. */
    @Column(name = "finished_at", length = 50)
    private String finishedAt;

    /** Etapa da apresentação publicada pelo facilitador (docs/ceremony-radar-retro-api.md). Só leitura no JSON. */
    @Column(name = "presentation_stage", length = 40)
    private String presentationStage;

    // --- Progresso de respostas: calculado na leitura, nunca persistido (docs/ceremony-radar-retro-api.md) ---

    @Transient
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer respondedCount;

    @Transient
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer completedCount;

    @Transient
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer participantsCount;

    /** OPEN|FINISHED derivado de {@code status} ('finished' = FINISHED). {@code status} segue sendo collecting|finished. */
    @JsonProperty("lifecycleStatus")
    public String getLifecycleStatus() {
        return "finished".equals(status) ? "FINISHED" : "OPEN";
    }
}
