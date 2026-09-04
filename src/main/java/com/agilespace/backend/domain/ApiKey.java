package com.agilespace.backend.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Chave de acesso programático (não é sessão de usuário) para a API pública
 * de leitura da Base de Conhecimento (/api/v1/knowledge/**), validada por
 * ApiKeyAuthenticationFilter. `keyHash` é o SHA-256 hex da chave crua (não
 * PBKDF2/salt como PasswordUtil usa pra senha de usuário — a chave em si já
 * tem entropia alta o bastante, e um hash determinístico permite lookup
 * direto por índice único em vez de varrer e comparar uma a uma).
 */
@Entity
@Table(name = "api_keys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true, length = 64)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String keyHash;

    private String ownerUserId;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime lastUsedAt;

    private LocalDateTime revokedAt;
}
