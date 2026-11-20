package com.agilespace.backend.repository;

import com.agilespace.backend.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, java.util.UUID> {
    Optional<ApiKey> findByKeyHashAndRevokedAtIsNull(String keyHash);
    List<ApiKey> findAllByOrderByCreatedAtDesc();

    /** Chaves ativas do dono (não revogadas e não vencidas): aviso do rebaixamento de LEAD (Etapa 4). */
    @org.springframework.data.jpa.repository.Query("SELECT COUNT(k) FROM ApiKey k WHERE k.ownerUserId = :ownerUserId "
            + "AND k.revokedAt IS NULL AND (k.expiresAt IS NULL OR k.expiresAt > :now)")
    long countActiveByOwner(@org.springframework.data.repository.query.Param("ownerUserId") String ownerUserId,
                            @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now);

    /** Usado pelo self-service (/api/api-keys, fase 4) — cada dono só vê as próprias. */
    List<ApiKey> findAllByOwnerUserIdOrderByCreatedAtDesc(String ownerUserId);
}
