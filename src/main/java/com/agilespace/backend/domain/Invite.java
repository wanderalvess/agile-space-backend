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

    /** Convite de equipe: link para várias pessoas (papel fixo, sem e-mail). false = convite individual, uso único. */
    @Column(name = "multi_use", nullable = false)
    @Builder.Default
    private boolean multiUse = false;

    /** Só multiuso: teto de pessoas. null = sem limite (só ADMIN cria assim). */
    @Column(name = "max_uses")
    private Integer maxUses;

    /** Só multiuso: quantas pessoas já entraram. Incrementado por UPDATE condicional, nunca por save() da entidade. */
    @Column(name = "uses_count", nullable = false)
    @Builder.Default
    private int usesCount = 0;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /** Só na resposta da página: vagas restantes (null = sem limite). */
    @Transient
    private Integer remainingUses;

    /** Só na resposta da página (multiuso): a conta logada já faz parte da squad ou já usou este convite. */
    @Transient
    private Boolean alreadyMember;

    /** Nome legível da squad, só na resposta da página do convite (a chave sozinha não diz nada a quem recebeu). */
    @Transient
    private String squadName;

    @PrePersist
    public void onPrePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
