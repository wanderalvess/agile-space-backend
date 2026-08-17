package com.agilespace.backend.repository;

import com.agilespace.backend.domain.PromptComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PromptCommentRepository extends JpaRepository<PromptComment, UUID> {
    List<PromptComment> findByPromptIdOrderByCreatedAtAsc(UUID promptId);
}
