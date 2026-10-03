package com.agilespace.backend.service;

import com.agilespace.backend.domain.SquadIssueSnapshot;
import com.agilespace.backend.domain.WorkItem;
import com.agilespace.backend.repository.SquadIssueSnapshotRepository;
import com.agilespace.backend.repository.SquadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Cerimônias (Poker/Planner/Showcase) gravam aqui, mas o dado mora em SquadIssueSnapshot —
 * a mesma linha que o sync real do Squad já popula com type/status/assignee/estimate vindos
 * do Jira — em vez da antiga tabela work_items separada, que nunca era alimentada por um
 * sync de verdade. Os métodos seguem devolvendo WorkItem só como formato de resposta, pra
 * não quebrar o contrato que WorkItemController/frontend já consomem (ver plano de
 * unificação Squad Pulse + jiradash, Fase 1).
 */
@Service
@RequiredArgsConstructor
public class WorkItemService {

    private final SquadIssueSnapshotRepository issueSnapshotRepository;
    private final SquadRepository squadRepository;

    private SquadIssueSnapshot findOrCreate(String squadId, String jiraKey) {
        return issueSnapshotRepository.findBySquadIdAndJiraKey(squadId, jiraKey)
                .orElseGet(() -> SquadIssueSnapshot.builder()
                        .dbId(squadId + "_" + jiraKey)
                        .squadId(squadId)
                        .jiraKey(jiraKey)
                        .build());
    }

    private WorkItem toWorkItemView(SquadIssueSnapshot s) {
        return WorkItem.builder()
                .id(s.getDbId())
                .squadId(s.getSquadId())
                .sprintId(s.getSprintId())
                .jiraKey(s.getJiraKey())
                .title(s.getJiraKey())
                .type(s.getType())
                .assigneeName(s.getAssigneeName())
                .assigneeId(s.getAssigneeId())
                .pointsEstimated(s.getPointsEstimated())
                .estimateSec(s.getEstimateSec())
                .remainingSec(s.getRemainingSec())
                .loggedSec(s.getLoggedSec())
                .status(s.getCeremonyStatus())
                .decisionFeedback(s.getDecisionFeedback())
                .parentKey(s.getParentKey())
                .parentTitle(s.getParentTitle())
                .build();
    }

    @Transactional
    public void estimateWorkItem(String squadId, String jiraKey, Double pointsEstimated) {
        SquadIssueSnapshot item = findOrCreate(squadId, jiraKey);
        if (item.getCeremonyStatus() == null || item.getCeremonyStatus().isBlank()) {
            item.setCeremonyStatus("backlog");
        }
        item.setPointsEstimated(pointsEstimated);
        issueSnapshotRepository.save(item);
    }

    @Transactional
    public void commitWorkItem(String squadId, String jiraKey, String sprintId) {
        SquadIssueSnapshot item = findOrCreate(squadId, jiraKey);
        item.setCeremonyStatus("committed");
        item.setSprintId(sprintId);
        issueSnapshotRepository.save(item);
    }

    @Transactional
    public void showcaseDecision(String squadId, String jiraKey, String status, String feedback) {
        SquadIssueSnapshot item = findOrCreate(squadId, jiraKey);
        item.setCeremonyStatus(status);
        item.setDecisionFeedback(feedback);
        item.setDecidedAt(LocalDateTime.now().toString());
        issueSnapshotRepository.save(item);
    }

    public List<WorkItem> getBacklogEstimated(String squadId) {
        return issueSnapshotRepository.findBySquadIdAndCeremonyStatus(squadId, "backlog").stream()
                .filter(w -> w.getPointsEstimated() != null && w.getPointsEstimated() > 0)
                .map(this::toWorkItemView)
                .toList();
    }

    public Map<String, Object> getSprintStats(String squadId, String sprintId) {
        List<SquadIssueSnapshot> items;
        if (sprintId == null || sprintId.isBlank() || "active".equalsIgnoreCase(sprintId.trim())) {
            String activeSprint = squadRepository.findById(squadId)
                    .map(com.agilespace.backend.domain.Squad::getActiveSprintId)
                    .filter(s -> s != null && !s.isBlank())
                    .orElse(null);

            if (activeSprint != null) {
                items = issueSnapshotRepository.findBySquadIdAndSprintId(squadId, activeSprint);
            } else {
                items = Collections.emptyList();
            }

            // Fallback: se nenhum item estiver amarrado ao ID exato, busca todos os itens ativos (não-backlog) da squad
            if (items.isEmpty()) {
                items = issueSnapshotRepository.findBySquadId(squadId).stream()
                        .filter(w -> w.getCeremonyStatus() != null && !"backlog".equalsIgnoreCase(w.getCeremonyStatus()))
                        .toList();
            }
        } else {
            items = issueSnapshotRepository.findBySquadIdAndSprintId(squadId, sprintId);
        }

        double velocityReal = 0;
        double previsto = 0;
        int carryOvers = 0;

        for (SquadIssueSnapshot item : items) {
            double pts = item.getPointsEstimated() != null ? item.getPointsEstimated() : 0;
            previsto += pts;
            if ("delivered".equals(item.getCeremonyStatus())) {
                velocityReal += pts;
            } else if ("carried_over".equals(item.getCeremonyStatus())) {
                carryOvers++;
            }
        }

        return Map.of(
                "velocityReal", velocityReal,
                "previsto", previsto,
                "entregue", velocityReal,
                "carryOvers", carryOvers
        );
    }

    public List<WorkItem> getAssignedWorkItems(String squadId, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Collections.emptyList();
        }
        return issueSnapshotRepository.findBySquadIdAndAssigneeIdentifier(squadId, identifier.trim()).stream()
                .map(this::toWorkItemView)
                .toList();
    }

    public List<WorkItem> getWorkItemsBySquadId(String squadId) {
        return issueSnapshotRepository.findBySquadId(squadId).stream()
                .map(this::toWorkItemView)
                .toList();
    }
}
