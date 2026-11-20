package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "password_reset_requests", indexes = {
    @Index(name = "idx_reset_email", columnList = "user_email"),
    @Index(name = "idx_reset_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PasswordResetRequest {

    @Id
    private String id;

    @Column(name = "user_email", nullable = false)
    private String userEmail;

    @Column(name = "user_name")
    private String userName;

    @Column(nullable = false)
    @Builder.Default
    private String status = "PENDING"; // "PENDING", "APPROVED", "REJECTED"

    @Column(name = "temp_password")
    private String tempPassword;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "approved_by")
    private String approvedBy;

    /** SHA-256 (hex) do token do link de redefinição; o token em si nunca é guardado. */
    @Column(name = "token_hash", length = 64)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String tokenHash;

    @Column(name = "token_expires_at")
    private LocalDateTime tokenExpiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    /** Token em claro: só existe na resposta que gera o link (não é persistido). */
    @Transient
    private String resetToken;

    @PrePersist
    public void onPrePersist() {
        if (requestedAt == null) {
            requestedAt = LocalDateTime.now();
        }
    }
}
