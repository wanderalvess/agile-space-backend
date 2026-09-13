package com.agilespace.backend.service;

import com.agilespace.backend.domain.JiraDashSnapshot;
import com.agilespace.backend.repository.JiraDashSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

// CRUD burro, sem TTL/staleness: quem decide buscar de novo no Jira é sempre
// o cliente (botão "Atualizar"). Ver JiraDashSnapshot para o porquê da JQL
// (não a squad) ser a chave do cache.
@Service
@RequiredArgsConstructor
public class JiraDashSnapshotService {

    private final JiraDashSnapshotRepository repository;

    // Mesma JQL (ignorando espaços redundantes) sempre gera o mesmo id —
    // dispensa uma tabela de lookup separada.
    private String normalizeKey(String jql) {
        String normalized = jql == null ? "" : jql.trim().replaceAll("\\s+", " ");
        return UUID.nameUUIDFromBytes(normalized.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public Optional<JiraDashSnapshot> get(String jql) {
        return repository.findById(normalizeKey(jql));
    }

    public JiraDashSnapshot save(String jql, JsonNode payload, String userId, String userName) {
        JiraDashSnapshot snapshot = JiraDashSnapshot.builder()
            .id(normalizeKey(jql))
            .jql(jql)
            .payload(payload)
            .fetchedByUserId(userId)
            .fetchedByName(userName)
            .build();
        return repository.save(snapshot);
    }
}
