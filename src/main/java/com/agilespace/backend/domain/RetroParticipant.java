package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "retro_participants", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"board_id", "user_id"}) // Corrigido para referenciar os nomes físicos das colunas
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetroParticipant {

    @Id
    private String dbId; // Formato: {boardId}_{userId}

    @Column(name = "user_id", nullable = false)
    private String id; // UID do participante (exposto como 'id' no JSON)

    @Column(name = "board_id", nullable = false)
    private String boardId;

    @Column(nullable = false)
    private String nickname;

    private String role; // AM, PO, PL, DEV, QA, etc.

    @Builder.Default
    private Boolean isCreator = false;

    private String globalRole;
}
