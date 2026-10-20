package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SprintPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SprintPlanRepository extends JpaRepository<SprintPlan, String> {
    List<SprintPlan> findBySquadIdOrderByStartDateDesc(String squadId);
    boolean existsBySquadIdAndJiraSprintId(String squadId, String jiraSprintId);
    Optional<SprintPlan> findFirstBySquadIdAndStatusInOrderByStartDateDesc(String squadId, Collection<String> statuses);
}
