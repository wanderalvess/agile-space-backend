package com.agilespace.backend.service;

import com.agilespace.backend.domain.SprintPlanning;
import com.agilespace.backend.repository.SprintPlanningRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SprintPlanningService {

    private final SprintPlanningRepository sprintPlanningRepository;

    @Transactional(readOnly = true)
    public Optional<SprintPlanning> getPlanner(String id) {
        return sprintPlanningRepository.findById(id);
    }

    @Transactional
    public SprintPlanning saveOrUpdatePlanner(SprintPlanning planner) {
        if (planner.getId() == null || planner.getId().trim().isEmpty()) {
            planner.setId(UUID.randomUUID().toString());
        }
        if (planner.getCreatedAt() == null || planner.getCreatedAt().trim().isEmpty()) {
            planner.setCreatedAt(new java.util.Date().toString());
        }
        planner.setUpdatedAt(new java.util.Date().toString());
        return sprintPlanningRepository.save(planner);
    }

    @Transactional
    public void deletePlanner(String id) {
        sprintPlanningRepository.deleteById(id);
    }
}
