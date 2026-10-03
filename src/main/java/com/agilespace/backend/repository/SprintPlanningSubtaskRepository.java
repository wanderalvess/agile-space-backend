package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SprintPlanningSubtask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SprintPlanningSubtaskRepository extends JpaRepository<SprintPlanningSubtask, String> {
    List<SprintPlanningSubtask> findByTaskIdInOrderByOrderAsc(List<String> taskIds);
    void deleteByTaskIdIn(List<String> taskIds);
}
