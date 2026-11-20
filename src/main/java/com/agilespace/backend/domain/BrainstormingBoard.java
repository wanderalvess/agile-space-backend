package com.agilespace.backend.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    // --- Cerimônia (V80). Todos opcionais: mural antigo = OPEN, facilitador = criador, sem limite de votos ---

    /** OPEN | FINISHED. Nulo no banco = OPEN (use {@link #getStatus()}). */
    @Column(name = "status", length = 20)
    private String status;

    /** Quando o mural foi encerrado (ISO-8601); nulo enquanto aberto. */
    @Column(name = "finished_at", length = 40)
    private String finishedAt;

    /** Facilitador atual. Nulo no banco = o criador (use {@link #getFacilitatorId()}). */
    @Column(name = "facilitator_id")
    private String facilitatorId;

    /** Máximo de votos por pessoa no mural; nulo = sem limite. */
    @Column(name = "votes_per_participant")
    private Integer votesPerParticipant;

    /** Só na saída: apelido do facilitador na lista de participantes (não é gravado). */
    @Transient
    private String facilitatorName;

    public static final String OPEN = "OPEN";
    public static final String FINISHED = "FINISHED";

    /** Sempre OPEN ou FINISHED (linha anterior à V80 devolve OPEN). */
    public String getStatus() {
        return FINISHED.equals(status) ? FINISHED : OPEN;
    }

    @JsonIgnore
    public boolean isFinished() {
        return FINISHED.equals(status);
    }

    /** O facilitador atual; mural sem passagem de facilitação (ou anterior à V80) é facilitado pelo criador. */
    public String getFacilitatorId() {
        return facilitatorId != null && !facilitatorId.isBlank() ? facilitatorId : creatorId;
    }
}
