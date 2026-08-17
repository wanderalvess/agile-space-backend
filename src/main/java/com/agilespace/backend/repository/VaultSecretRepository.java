package com.agilespace.backend.repository;

import com.agilespace.backend.domain.VaultSecret;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VaultSecretRepository extends JpaRepository<VaultSecret, String> {
}
