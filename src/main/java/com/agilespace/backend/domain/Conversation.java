package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Conversa direta entre duas pessoas (V70). O par é canônico: {@code userAId < userBId}, então existe no máximo uma
 * conversa por par (constraint uq_conversations_pair). Cada lado guarda a própria última leitura.
 */
@Entity
@Table(name = "conversations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Conversation {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_a_id", nullable = false)
    private String userAId;

    @Column(name = "user_b_id", nullable = false)
    private String userBId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Última mensagem (ou criação): ordena a lista de conversas. */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_read_at_a")
    private LocalDateTime lastReadAtA;

    @Column(name = "last_read_at_b")
    private LocalDateTime lastReadAtB;

    public boolean hasParticipant(String userId) {
        return userId != null && (userId.equals(userAId) || userId.equals(userBId));
    }

    public String otherParticipant(String userId) {
        return userId != null && userId.equals(userAId) ? userBId : userAId;
    }

    public LocalDateTime lastReadAtOf(String userId) {
        return userId != null && userId.equals(userAId) ? lastReadAtA : lastReadAtB;
    }

    public void markReadBy(String userId, LocalDateTime at) {
        if (userId == null) return;
        if (userId.equals(userAId)) lastReadAtA = at;
        else if (userId.equals(userBId)) lastReadAtB = at;
    }
}
