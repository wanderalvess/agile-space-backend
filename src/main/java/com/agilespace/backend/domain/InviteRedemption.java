package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Uma pessoa que entrou por um convite de equipe (multiuso). Única por (convite, usuário). */
@Entity
@Table(name = "invite_redemptions", uniqueConstraints = {
    @UniqueConstraint(name = "uq_invite_redemptions_invite_user", columnNames = {"invite_id", "user_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InviteRedemption {

    @Id
    private String id;

    @Column(name = "invite_id", nullable = false)
    private String inviteId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void onPrePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
