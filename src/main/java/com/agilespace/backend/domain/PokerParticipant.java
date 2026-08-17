package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "poker_participants", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"room_id", "user_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PokerParticipant {

    @Id
    private String dbId; // Formato: {roomId}_{userId}

    @Column(name = "user_id", nullable = false)
    private String id; // UID do participante (exposto como 'id' no JSON)

    @Column(name = "room_id", nullable = false)
    private String roomId;

    @Column(nullable = false)
    private String nickname;

    private String email; // Adicionado para evitar UnrecognizedPropertyException do Jackson

    @Column(length = 50)
    private String role; // dev, qa, organizador, spectator

    @Builder.Default
    private Boolean isFacilitator = false;

    private String globalRole;

    private String lastSeen;
}
