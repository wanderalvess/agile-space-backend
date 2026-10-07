package com.agilespace.backend.repository;

import com.agilespace.backend.domain.KnowledgeUserAiSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface KnowledgeUserAiSettingsRepository extends JpaRepository<KnowledgeUserAiSettings, String> {
}
