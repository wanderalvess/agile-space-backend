package com.agilespace.backend.repository;

import com.agilespace.backend.domain.KnowledgeDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface KnowledgeRepository extends JpaRepository<KnowledgeDocument, UUID> {

    Optional<KnowledgeDocument> findByTdnId(String tdnId);

    Page<KnowledgeDocument> findByStatusNot(String status, Pageable pageable);

    @Query("SELECT d FROM KnowledgeDocument d WHERE d.status <> :deletedStatus AND " +
           "(LOWER(d.title) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(d.content) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(d.category) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(d.fullPath) LIKE LOWER(CONCAT('%', :query, '%')))")
    Page<KnowledgeDocument> searchActive(
            @Param("query") String query,
            @Param("deletedStatus") String deletedStatus,
            Pageable pageable
    );
}
