package com.agilespace.backend.repository;

import com.agilespace.backend.domain.PromptVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PromptVersionRepository extends JpaRepository<PromptVersion, UUID> {
    List<PromptVersion> findByPromptIdOrderByVersionNumberDesc(UUID promptId);

    Optional<PromptVersion> findByPromptIdAndVersionNumber(UUID promptId, Integer versionNumber);

    @Query("SELECT COALESCE(MAX(v.versionNumber), 0) FROM PromptVersion v WHERE v.promptId = :promptId")
    int maxVersionNumber(@Param("promptId") UUID promptId);
}
