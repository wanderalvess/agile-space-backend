package com.agilespace.backend.repository;

import com.agilespace.backend.domain.ActionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ActionPlanRepository extends JpaRepository<ActionPlan, UUID> {
    List<ActionPlan> findBySprintIdOrderByCreatedAtDesc(String sprintId);

    @Query("SELECT p FROM ActionPlan p WHERE UPPER(p.squadId) = UPPER(:squadId) ORDER BY p.createdAt DESC")
    List<ActionPlan> findBySquad(@Param("squadId") String squadId, Pageable pageable);
}
