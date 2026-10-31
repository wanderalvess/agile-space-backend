package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SquadPokerTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SquadPokerTemplateRepository extends JpaRepository<SquadPokerTemplate, String> {
    List<SquadPokerTemplate> findBySquadIdIgnoreCaseOrderByCreatedAtDesc(String squadId);
    long countBySquadIdIgnoreCase(String squadId);
}
