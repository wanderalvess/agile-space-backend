package com.agilespace.backend.service;

import com.agilespace.backend.domain.VaultSecret;
import com.agilespace.backend.repository.VaultSecretRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class VaultSecretService {

    @Autowired
    private VaultSecretRepository repository;

    @Transactional
    public VaultSecret save(VaultSecret secret) {
        if (secret.getId() == null || secret.getId().isEmpty()) {
            secret.setId(UUID.randomUUID().toString());
        }
        secret.setCreatedAt(LocalDateTime.now());
        
        if ("1h".equals(secret.getExpirationType())) {
            secret.setExpiresAt(LocalDateTime.now().plusHours(1));
        } else if ("24h".equals(secret.getExpirationType())) {
            secret.setExpiresAt(LocalDateTime.now().plusDays(1));
        } else {
            secret.setExpiresAt(null); // 'once' expirará apenas na leitura
        }
        
        secret.setBurned(false);
        return repository.save(secret);
    }

    @Transactional
    public VaultSecret getAndProcessExpiration(String id) {
        VaultSecret secret = repository.findById(id).orElse(null);
        if (secret == null) {
            return null;
        }

        // Verifica expiração por tempo
        if (secret.getExpiresAt() != null && secret.getExpiresAt().isBefore(LocalDateTime.now())) {
            repository.delete(secret);
            return null;
        }

        // Se já foi queimado
        if (secret.isBurned()) {
            repository.delete(secret);
            return null;
        }

        // Se for leitura única ("once"), deleta imediatamente ou marca como queimado
        if ("once".equals(secret.getExpirationType())) {
            // Deletamos do banco por privacidade estrita
            repository.delete(secret);
        }

        return secret;
    }
}
