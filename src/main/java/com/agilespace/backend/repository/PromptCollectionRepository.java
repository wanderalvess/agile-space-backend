package com.agilespace.backend.repository;

import com.agilespace.backend.domain.PromptCollection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PromptCollectionRepository extends JpaRepository<PromptCollection, UUID> {
    Page<PromptCollection> findByVisibility(String visibility, Pageable pageable);
    Page<PromptCollection> findByOwnerId(String ownerId, Pageable pageable);
    Page<PromptCollection> findByOwnerIdAndVisibility(String ownerId, String visibility, Pageable pageable);
    List<PromptCollection> findDistinctByItemsId(UUID promptId);

    @org.springframework.data.jpa.repository.Query("SELECT COUNT(c) FROM PromptCollection c WHERE c.visibility = 'public'")
    long countPublic();

    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(c) FROM PromptCollection c WHERE c.visibility = 'public' OR c.ownerId = :ownerId")
    long countVisibleTo(@org.springframework.data.repository.query.Param("ownerId") String ownerId);
}
