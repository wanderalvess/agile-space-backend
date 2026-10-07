package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SprintPlanningTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SprintPlanningTaskRepository extends JpaRepository<SprintPlanningTask, String> {
    List<SprintPlanningTask> findByPlanningIdOrderByOrderAsc(String planningId);
    void deleteByPlanningId(String planningId);
}
