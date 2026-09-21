package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SquadPersonConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SquadPersonConfigRepository extends JpaRepository<SquadPersonConfig, String> {
    Optional<SquadPersonConfig> findBySquadIdAndSprintIdAndJiraAccountId(String squadId, String sprintId, String jiraAccountId);
    List<SquadPersonConfig> findBySquadIdAndSprintId(String squadId, String sprintId);
}
