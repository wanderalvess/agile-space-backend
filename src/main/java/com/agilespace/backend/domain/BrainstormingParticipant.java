package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "brainstorming_participants", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"board_id", "user_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BrainstormingParticipant {

    @Id
    private String dbId; // Formato: {boardId}_{userId}

    @Column(name = "user_id", nullable = false)
    private String id; // UID do usuário (exposto como 'id' no JSON)

    @Column(name = "board_id", nullable = false)
    private String boardId;

    @Column(nullable = false)
    private String nickname;

    @Column(length = 50)
    private String role;

    @Builder.Default
    private Boolean isCreator = false;

    private String lastActive;
}
