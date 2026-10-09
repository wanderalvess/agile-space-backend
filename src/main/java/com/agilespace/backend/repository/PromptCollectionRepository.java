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
}
