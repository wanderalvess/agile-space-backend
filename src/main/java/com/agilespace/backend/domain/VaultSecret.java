package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "vault_secrets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VaultSecret {

    @Id
    private String id; // ID aleatório gerado (UUID)

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload; // Ciphertext criptografado localmente

    @Column(nullable = false)
    private String iv; // Vetor de inicialização (Base64)

    @Column(name = "expiration_type", nullable = false)
    private String expirationType; // "once", "1h", "24h"

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt; // Nulo para "once"

    @Column(name = "is_burned", nullable = false)
    private boolean isBurned; // Indica se o segredo já foi queimado/lido
}
