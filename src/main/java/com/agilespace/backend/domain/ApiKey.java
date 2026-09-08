package com.agilespace.backend.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Chave de acesso programático (não é sessão de usuário) para a API pública
 * de leitura/escrita da Base de Conhecimento, Prompt Hub, Squad e Poker
 * (/api/v1/** e /mcp/**), validada por ApiKeyAuthenticationFilter. `keyHash`
 * é o SHA-256 hex da chave crua (não PBKDF2/salt como PasswordUtil usa pra
 * senha de usuário — a chave em si já tem entropia alta o bastante, e um
 * hash determinístico permite lookup direto por índice único em vez de
 * varrer e comparar uma a uma).
 */
@Entity
@Table(name = "api_keys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
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

    /**
     * Papel do dono no momento em que a chave foi criada (ver {@link ApiKeyScope}
     * e {@link com.agilespace.backend.domain.UserRole} pros valores válidos).
     * Não é reavaliado depois — se o papel do usuário mudar, a chave continua
     * valendo com o papel antigo até ser revogada e recriada. Null pra toda
     * chave criada antes deste campo existir (ver {@link #hasFullAccessGrandfathered()}).
     */
    private String ownerRole;

    /**
     * Operações que esta chave autoriza (ver {@link ApiKeyScope}) — armazenado
     * como String livre, não enum JPA, mesmo padrão de
     * {@link KnowledgeDocument#getTags()}: valida-se no controller/tool
     * (ApiKeyScope.isValid), não no banco.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "api_key_scopes", joinColumns = @JoinColumn(name = "api_key_id"))
    @Column(name = "scope")
    @Builder.Default
    private Set<String> scopes = new HashSet<>();

    /**
     * Restringe a chave a uma squad (tools/endpoints que recebem squadId
     * comparam com este valor). Null = sem restrição — hoje é o único modo
     * que existe, e continua sendo o comportamento de toda chave criada por
     * ADMIN/LEAD via /api/admin/api-keys.
     */
    private String squadId;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime lastUsedAt;

    private LocalDateTime revokedAt;

    /**
     * Chave criada antes deste campo existir (ownerRole null) por alguém que
     * era ADMIN/LEAD na época — trata como acesso total, preservando o
     * comportamento anterior a este plano. Chave nova sempre tem ownerRole e
     * scopes explícitos; esta função só existe pra não quebrar as chaves já
     * emitidas em produção.
     */
    public boolean hasFullAccessGrandfathered() {
        return ownerRole == null;
    }
}
