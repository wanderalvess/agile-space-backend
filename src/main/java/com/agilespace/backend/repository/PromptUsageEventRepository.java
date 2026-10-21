package com.agilespace.backend.repository;

import com.agilespace.backend.domain.PromptUsageEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface PromptUsageEventRepository extends JpaRepository<PromptUsageEvent, UUID> {
    List<PromptUsageEvent> findByPromptIdAndOccurredAtGreaterThanEqual(UUID promptId, Instant since);
}
