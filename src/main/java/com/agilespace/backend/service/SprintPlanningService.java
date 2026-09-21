package com.agilespace.backend.service;

import com.agilespace.backend.domain.SprintPlanning;
import com.agilespace.backend.repository.SprintPlanningRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
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
    public SprintPlanning saveOrUpdatePlanner(SprintPlanning planner, String callerId) {
        if (planner.getTitle() == null || planner.getTitle().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title é obrigatório");
        }
        requireArrayIfPresent(planner.getTasks(), "tasks");
        requireArrayIfPresent(planner.getMembers(), "members");
        requireObjectIfPresent(planner.getSettings(), "settings");
        requireArrayIfPresent(planner.getImportedPokerRoomIds(), "importedPokerRoomIds");

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

    // tasks/members/settings continuam JSONB opaco (ver memória do projeto sobre o Sprint
    // Planner/Showcase) — isto só garante que o shape de topo bate com o que o frontend espera,
    // não valida os campos internos de cada task/member.
    private void requireArrayIfPresent(JsonNode node, String field) {
        if (node != null && !node.isNull() && !node.isArray()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " precisa ser uma lista JSON");
        }
    }

    private void requireObjectIfPresent(JsonNode node, String field) {
        if (node != null && !node.isNull() && !node.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " precisa ser um objeto JSON");
        }
    }
}
