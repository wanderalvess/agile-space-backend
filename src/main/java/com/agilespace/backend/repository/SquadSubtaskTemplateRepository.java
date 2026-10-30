package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SquadSubtaskTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SquadSubtaskTemplateRepository extends JpaRepository<SquadSubtaskTemplate, String> {
    List<SquadSubtaskTemplate> findBySquadIdIgnoreCaseOrderByPositionAsc(String squadId);
    void deleteBySquadIdIgnoreCase(String squadId);
}
