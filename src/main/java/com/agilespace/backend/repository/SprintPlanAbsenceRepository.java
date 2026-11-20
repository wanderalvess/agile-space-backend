package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SprintPlanAbsence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SprintPlanAbsenceRepository extends JpaRepository<SprintPlanAbsence, String> {
    List<SprintPlanAbsence> findByPlanIdOrderByStartDateAsc(String planId);
}
