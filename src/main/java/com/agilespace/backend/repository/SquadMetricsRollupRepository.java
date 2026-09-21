package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SquadMetricsRollup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SquadMetricsRollupRepository extends JpaRepository<SquadMetricsRollup, String> {
    Optional<SquadMetricsRollup> findBySquadIdAndSprintId(String squadId, String sprintId);
    List<SquadMetricsRollup> findBySquadId(String squadId);
}
