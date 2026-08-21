package com.agilespace.backend.service;

import com.agilespace.backend.domain.WorkItem;
import com.agilespace.backend.repository.WorkItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkItemService {

    private final WorkItemRepository workItemRepository;

    @Transactional
    public void estimateWorkItem(String squadId, String jiraKey, Double pointsEstimated) {
        WorkItem item = workItemRepository.findBySquadIdAndJiraKey(squadId, jiraKey)
            .orElseGet(() -> WorkItem.builder()
                .id(squadId + "_" + jiraKey)
                .squadId(squadId)
                .jiraKey(jiraKey)
                .title(jiraKey)
                .status("backlog")
                .createdAt(LocalDateTime.now())
                .build());

        item.setPointsEstimated(pointsEstimated);
        item.setUpdatedAt(LocalDateTime.now());
        workItemRepository.save(item);
    }

    @Transactional
    public void commitWorkItem(String squadId, String jiraKey, String sprintId) {
        WorkItem item = workItemRepository.findBySquadIdAndJiraKey(squadId, jiraKey)
            .orElseGet(() -> WorkItem.builder()
                .id(squadId + "_" + jiraKey)
                .squadId(squadId)
                .jiraKey(jiraKey)
                .title(jiraKey)
                .createdAt(LocalDateTime.now())
                .build());

        item.setStatus("committed");
        item.setSprintId(sprintId);
        item.setUpdatedAt(LocalDateTime.now());
        workItemRepository.save(item);
    }

    @Transactional
    public void showcaseDecision(String squadId, String jiraKey, String status, String feedback) {
        WorkItem item = workItemRepository.findBySquadIdAndJiraKey(squadId, jiraKey)
            .orElseGet(() -> WorkItem.builder()
                .id(squadId + "_" + jiraKey)
                .squadId(squadId)
                .jiraKey(jiraKey)
                .title(jiraKey)
                .createdAt(LocalDateTime.now())
                .build());

        item.setStatus(status);
        item.setDecisionFeedback(feedback);
        item.setDecidedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        workItemRepository.save(item);
    }

    public List<WorkItem> getBacklogEstimated(String squadId) {
        return workItemRepository.findBySquadIdAndStatus(squadId, "backlog").stream()
            .filter(w -> w.getPointsEstimated() != null && w.getPointsEstimated() > 0)
            .toList();
    }

    public java.util.Map<String, Object> getSprintStats(String squadId, String sprintId) {
        List<WorkItem> items = workItemRepository.findBySquadIdAndSprintId(squadId, sprintId);
        double velocityReal = 0;
        double previsto = 0;
        int carryOvers = 0;
        
        for (WorkItem item : items) {
            double pts = item.getPointsEstimated() != null ? item.getPointsEstimated() : 0;
            previsto += pts;
            if ("delivered".equals(item.getStatus())) {
                velocityReal += pts;
            } else if ("carried_over".equals(item.getStatus())) {
                carryOvers++;
            }
        }
        
        return java.util.Map.of(
            "velocityReal", velocityReal,
            "previsto", previsto,
            "entregue", velocityReal,
            "carryOvers", carryOvers
        );
    }

    public List<WorkItem> getAssignedWorkItems(String squadId, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return java.util.Collections.emptyList();
        }
        return workItemRepository.findBySquadIdAndUserIdentifier(squadId, identifier.trim());
    }

    public List<WorkItem> getWorkItemsBySquadId(String squadId) {
        return workItemRepository.findBySquadId(squadId);
    }
}
