package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "health_check_votes", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"board_id", "participant_id", "dimension_key"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HealthCheckVote {

    @Id
    private String id; // Formato: {boardId}_{participantId}_{dimensionKey}

    @Column(name = "board_id", nullable = false)
    private String boardId;

    @Column(name = "participant_id", nullable = false)
    private String participantId;

    @Column(length = 50)
    private String participantRole;

    @Column(name = "dimension_key", nullable = false, length = 100)
    private String dimensionKey;

    @Column(nullable = false, length = 50)
    private String value;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(name = "vote_timestamp", nullable = false) // Evita palavra reservada 'timestamp' do Postgres
    private String timestamp;
}
