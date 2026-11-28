package com.agilespace.backend.repository;

import com.agilespace.backend.domain.KnowledgeChunk;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, UUID> {

    List<KnowledgeChunk> findByDocumentIdOrderByOrdinalAsc(UUID documentId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM KnowledgeChunk c WHERE c.documentId = :documentId")
    void deleteAllByDocumentId(@Param("documentId") UUID documentId);

    /** Trechos ainda sem embedding, dos documentos que entram em busca (publicado/indexado). */
    @Query("SELECT c FROM KnowledgeChunk c WHERE c.embedding IS NULL AND c.documentId IN " +
           "(SELECT d.id FROM KnowledgeDocument d WHERE d.status IN ('published','indexed')) ORDER BY c.createdAt, c.ordinal")
    List<KnowledgeChunk> findPending(Pageable pageable);

    List<KnowledgeChunk> findByIdIn(Collection<UUID> ids);
}
