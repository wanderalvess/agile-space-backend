package com.agilespace.backend.repository;

import com.agilespace.backend.domain.KnowledgeTokenUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeTokenUsageRepository extends JpaRepository<KnowledgeTokenUsage, String> {

    List<KnowledgeTokenUsage> findTop10ByOrderByTotalTokensDesc();
}
