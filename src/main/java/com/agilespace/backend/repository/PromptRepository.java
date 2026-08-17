package com.agilespace.backend.repository;

import com.agilespace.backend.domain.Prompt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PromptRepository extends JpaRepository<Prompt, UUID> {

    Page<Prompt> findByVisibility(String visibility, Pageable pageable);

    Page<Prompt> findByAuthorId(String authorId, Pageable pageable);

    Page<Prompt> findByAuthorIdAndVisibility(String authorId, String visibility, Pageable pageable);

    @Query("SELECT p FROM Prompt p WHERE p.visibility = :visibility AND " +
           "(LOWER(p.title) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(p.description) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(p.content) LIKE LOWER(CONCAT('%', :query, '%')))")
    Page<Prompt> searchPublic(@Param("query") String query, @Param("visibility") String visibility, Pageable pageable);
}
