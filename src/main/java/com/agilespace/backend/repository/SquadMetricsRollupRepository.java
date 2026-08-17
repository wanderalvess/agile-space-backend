package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SquadMetricsRollup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SquadMetricsRollupRepository extends JpaRepository<SquadMetricsRollup, String> {
}
