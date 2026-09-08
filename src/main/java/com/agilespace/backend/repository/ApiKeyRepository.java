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

    /** Usado pelo self-service (/api/api-keys, fase 4) — cada dono só vê as próprias. */
    List<ApiKey> findAllByOwnerUserIdOrderByCreatedAtDesc(String ownerUserId);
}
