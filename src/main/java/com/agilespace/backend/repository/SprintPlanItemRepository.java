package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SprintPlanItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SprintPlanItemRepository extends JpaRepository<SprintPlanItem, String> {
    List<SprintPlanItem> findByPlanIdOrderBySortOrderAsc(String planId);
}
