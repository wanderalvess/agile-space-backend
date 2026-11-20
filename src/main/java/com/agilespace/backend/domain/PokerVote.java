package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
// Unicidade (sala, participante, tarefa) vive no índice uq_poker_votes_room_participant_issue (V38):
// salas assíncronas guardam um voto por tarefa.
@Table(name = "poker_votes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PokerVote {

    @Id
    private String id; // Chave estruturada no formato: {roomId}_{participantId}

    @Column(name = "room_id", nullable = false)
    private String roomId;

    @Column(name = "participant_id", nullable = false) // Explicitamente mapeado para garantir conformidade com UniqueConstraint
    private String participantId;

    @Column(nullable = false, length = 50)
    private String value;

    @Column(name = "vote_timestamp", nullable = false) // Mapeado para evitar palavra reservada 'timestamp' do Postgres
    private String timestamp;

    private String issueId;

    private String participantNickname;

    @Column(length = 50)
    private String participantRole;

    private String participantGlobalRole;

    @Column(length = 50)
    private String confidence; // low, medium, high
}
