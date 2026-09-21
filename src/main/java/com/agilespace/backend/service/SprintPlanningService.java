package com.agilespace.backend.service;

import com.agilespace.backend.domain.SprintPlanning;
import com.agilespace.backend.repository.SprintPlanningRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SprintPlanningService {

    // Mesma classe de proteção que ShowcaseSessionService aplica a tasks/members — os dois
    // são JSONB opaco sujeito ao mesmo risco de payload sem limite.
    private static final int MAX_TASKS = 2000;
    private static final int MAX_MEMBERS = 200;

    private final SprintPlanningRepository sprintPlanningRepository;

    @Transactional(readOnly = true)
    public Optional<SprintPlanning> getPlanner(String id) {
        return sprintPlanningRepository.findById(id);
    }

    @Transactional
    public SprintPlanning saveOrUpdatePlanner(SprintPlanning planner, String callerId) {
        ValidationSupport.requireNonBlank(planner.getTitle(), "title");
        ValidationSupport.requireArrayIfPresent(planner.getTasks(), "tasks");
        ValidationSupport.requireArrayIfPresent(planner.getMembers(), "members");
        ValidationSupport.requireObjectIfPresent(planner.getSettings(), "settings");
        ValidationSupport.requireArrayIfPresent(planner.getImportedPokerRoomIds(), "importedPokerRoomIds");
        ValidationSupport.requireMaxSize(planner.getTasks(), MAX_TASKS, "tasks");
        ValidationSupport.requireMaxSize(planner.getMembers(), MAX_MEMBERS, "members");

        if (planner.getId() != null && !planner.getId().trim().isEmpty()) {
            sprintPlanningRepository.findById(planner.getId()).ifPresent(existing -> {
                planner.setCreatedBy(existing.getCreatedBy() != null ? existing.getCreatedBy() : callerId);
                planner.setCreatedAt(existing.getCreatedAt());
            });
        } else {
            planner.setId(UUID.randomUUID().toString());
        }
        if (planner.getCreatedBy() == null || planner.getCreatedBy().trim().isEmpty()) {
            planner.setCreatedBy(callerId);
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

    @Transactional(readOnly = true)
    public List<SprintPlanning> listReadyForPoker(int limit) {
        return sprintPlanningRepository.findReadyForPoker(limit);
    }
}
