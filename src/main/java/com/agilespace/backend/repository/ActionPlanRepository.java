package com.agilespace.backend.repository;

import com.agilespace.backend.domain.ActionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ActionPlanRepository extends JpaRepository<ActionPlan, UUID> {
    List<ActionPlan> findBySprintIdOrderByCreatedAtDesc(String sprintId);
}
