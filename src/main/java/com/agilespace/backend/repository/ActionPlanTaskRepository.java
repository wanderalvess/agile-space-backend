package com.agilespace.backend.repository;

import com.agilespace.backend.domain.ActionPlanTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ActionPlanTaskRepository extends JpaRepository<ActionPlanTask, UUID> {
    List<ActionPlanTask> findByBoardIdOrderByOrderAsc(UUID boardId);
}
