package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** Item favoritado por um usuário (favoritos são pessoais e vivem no servidor). */
@Entity
@Table(name = "prompt_favorites")
@IdClass(PromptFavorite.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromptFavorite {

    @Id
    @Column(name = "user_id", nullable = false)
    private String userId;

    @Id
    @Column(name = "prompt_id", nullable = false)
    private UUID promptId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private String userId;
        private UUID promptId;
    }
}
