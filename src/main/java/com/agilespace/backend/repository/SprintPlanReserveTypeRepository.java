package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SprintPlanReserveType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SprintPlanReserveTypeRepository extends JpaRepository<SprintPlanReserveType, String> {
    List<SprintPlanReserveType> findByPlanIdOrderByLabelAsc(String planId);
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from SprintPlanReserveType e where e.planId = :planId")
    int deleteAllForPlan(@org.springframework.data.repository.query.Param("planId") String planId);
}
