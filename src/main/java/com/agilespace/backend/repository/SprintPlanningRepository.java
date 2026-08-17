package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SprintPlanning;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SprintPlanningRepository extends JpaRepository<SprintPlanning, String> {
}
