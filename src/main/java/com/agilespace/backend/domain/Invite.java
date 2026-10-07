package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "invites", indexes = {
    @Index(name = "idx_invites_token", columnList = "token", unique = true),
    @Index(name = "idx_invites_squad_id", columnList = "squad_id"),
    @Index(name = "idx_invites_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invite {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String token;

    @Column(name = "squad_id", nullable = false)
    private String squadId;

    @Column(name = "role_name", nullable = false)
    private String roleName;

    /** Opcional: se preenchido, só esse e-mail pode aceitar o convite. */
    @Column(name = "email")
    private String email;

    @Column(name = "invited_by", nullable = false)
    private String invitedBy;

    @Column(nullable = false)
    @Builder.Default
    private String status = "PENDING"; // "PENDING", "ACCEPTED", "REVOKED", "EXPIRED"

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "accepted_by_user_id")
    private String acceptedByUserId;

    @PrePersist
    public void onPrePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
