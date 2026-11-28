package com.agilespace.backend.repository;

import com.agilespace.backend.domain.KnowledgeDocRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface KnowledgeDocRelationRepository extends JpaRepository<KnowledgeDocRelation, UUID> {

    List<KnowledgeDocRelation> findByDocumentId(UUID documentId);

    void deleteByDocumentIdAndKind(UUID documentId, String kind);
}
