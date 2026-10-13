package com.agilespace.backend.repository;

import com.agilespace.backend.domain.KnowledgeDocumentVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KnowledgeDocumentVersionRepository extends JpaRepository<KnowledgeDocumentVersion, UUID> {

    List<KnowledgeDocumentVersion> findByDocumentIdOrderByVersionNumberDesc(UUID documentId);

    Optional<KnowledgeDocumentVersion> findByDocumentIdAndVersionNumber(UUID documentId, int versionNumber);

    Optional<KnowledgeDocumentVersion> findTopByDocumentIdOrderByVersionNumberDesc(UUID documentId);

    long countByDocumentId(UUID documentId);
}
