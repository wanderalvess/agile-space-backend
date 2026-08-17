package com.agilespace.backend.service;

import com.agilespace.backend.domain.*;
import com.agilespace.backend.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SquadService {

    private final SquadRepository squadRepository;
    private final SquadMetricsRollupRepository rollupRepository;
    private final SquadIssueSnapshotRepository issueSnapshotRepository;
    private final SquadMemberRepository memberRepository;
    private final SquadMemberMetricRepository memberMetricRepository;
    private final SquadDailySnapshotRepository dailySnapshotRepository;
    private final SquadIssueWorklogCacheRepository worklogCacheRepository;

    // ----- Squad Config -----
    @Transactional(readOnly = true)
    public Optional<Squad> getSquad(String squadId) {
        return squadRepository.findById(squadId);
    }

    @Transactional
    public Squad saveSquad(Squad squad) {
        return squadRepository.save(squad);
    }

    // ----- Metrics Rollup -----
    @Transactional(readOnly = true)
    public Optional<SquadMetricsRollup> getRollup(String squadId) {
        return rollupRepository.findById(squadId);
    }

    @Transactional
    public SquadMetricsRollup saveRollup(SquadMetricsRollup rollup) {
        rollup.setSquadId(rollup.getSquadId());
        return rollupRepository.save(rollup);
    }

    // ----- Issue Snapshots -----
    @Transactional(readOnly = true)
    public List<SquadIssueSnapshot> getIssues(String squadId, String sprintId) {
        if (sprintId != null && !sprintId.isBlank()) {
            return issueSnapshotRepository.findBySquadIdAndSprintId(squadId, sprintId);
        }
        return issueSnapshotRepository.findBySquadId(squadId);
    }

    @Transactional(readOnly = true)
    public List<SquadIssueSnapshot> getIssuesByAssignee(String squadId, String assigneeId) {
        return issueSnapshotRepository.findBySquadIdAndAssigneeId(squadId, assigneeId);
    }

    @Transactional(readOnly = true)
    public Optional<SquadIssueSnapshot> getIssueByKey(String squadId, String jiraKey) {
        return issueSnapshotRepository.findBySquadIdAndJiraKey(squadId, jiraKey);
    }

    @Transactional
    public List<SquadIssueSnapshot> batchUpsertIssues(String squadId, List<SquadIssueSnapshot> snapshots) {
        for (SquadIssueSnapshot snap : snapshots) {
            snap.setSquadId(squadId);
            if (snap.getDbId() == null || snap.getDbId().isBlank()) {
                snap.setDbId(squadId + "_" + snap.getJiraKey());
            }
        }
        return issueSnapshotRepository.saveAll(snapshots);
    }

    @Transactional
    public void batchDeleteIssues(String squadId, List<String> keys) {
        issueSnapshotRepository.deleteBySquadIdAndJiraKeyIn(squadId, keys);
    }

    // ----- Members -----
    @Transactional(readOnly = true)
    public List<SquadMember> getMembers(String squadId) {
        return memberRepository.findBySquadIdOrderByDisplayNameAsc(squadId);
    }

    @Transactional
    public SquadMember saveMember(String squadId, String jiraAccountId, SquadMember member) {
        member.setSquadId(squadId);
        member.setJiraAccountId(jiraAccountId);
        member.setDbId(squadId + "_" + jiraAccountId);

        // Merge: se já existe, preserva campos que não vieram no payload
        Optional<SquadMember> existing = memberRepository.findBySquadIdAndJiraAccountId(squadId, jiraAccountId);
        if (existing.isPresent()) {
            SquadMember e = existing.get();
            if (member.getDisplayName() == null) member.setDisplayName(e.getDisplayName());
            if (member.getCapacityHoursPerDay() == null) member.setCapacityHoursPerDay(e.getCapacityHoursPerDay());
            if (member.getClaimedByUid() == null) member.setClaimedByUid(e.getClaimedByUid());
        }
        return memberRepository.save(member);
    }

    @Transactional
    public List<SquadMember> batchUpsertMembers(String squadId, List<SquadMember> members) {
        for (SquadMember m : members) {
            m.setSquadId(squadId);
            if (m.getDbId() == null || m.getDbId().isBlank()) {
                m.setDbId(squadId + "_" + m.getJiraAccountId());
            }
        }
        return memberRepository.saveAll(members);
    }

    // ----- Member Metrics -----
    @Transactional(readOnly = true)
    public List<SquadMemberMetric> getMemberMetrics(String squadId) {
        return memberMetricRepository.findBySquadId(squadId);
    }

    @Transactional
    public List<SquadMemberMetric> batchUpsertMemberMetrics(String squadId, List<SquadMemberMetric> metrics) {
        for (SquadMemberMetric m : metrics) {
            m.setSquadId(squadId);
            if (m.getDbId() == null || m.getDbId().isBlank()) {
                m.setDbId(squadId + "_" + m.getAssigneeId());
            }
        }
        memberMetricRepository.deleteBySquadId(squadId);
        return memberMetricRepository.saveAll(metrics);
    }

    // ----- Daily Snapshots -----
    @Transactional(readOnly = true)
    public List<SquadDailySnapshot> getDailySnapshots(String squadId, String since) {
        if (since != null && !since.isBlank()) {
            return dailySnapshotRepository.findBySquadIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(squadId, since);
        }
        return dailySnapshotRepository.findBySquadIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(squadId, "0000-00-00");
    }

    @Transactional
    public List<SquadDailySnapshot> batchUpsertDailySnapshots(String squadId, List<SquadDailySnapshot> snapshots) {
        for (SquadDailySnapshot s : snapshots) {
            s.setSquadId(squadId);
            if (s.getDbId() == null || s.getDbId().isBlank()) {
                s.setDbId(squadId + "_" + s.getSnapshotDate());
            }
        }
        return dailySnapshotRepository.saveAll(snapshots);
    }

    // ----- Worklog Cache -----
    @Transactional(readOnly = true)
    public List<SquadIssueWorklogCache> getWorklogCache(String squadId, String sprintId) {
        if (sprintId != null && !sprintId.isBlank()) {
            return worklogCacheRepository.findBySquadIdAndSprintId(squadId, sprintId);
        }
        return worklogCacheRepository.findBySquadId(squadId);
    }

    @Transactional
    public List<SquadIssueWorklogCache> batchUpsertWorklogCache(String squadId, List<SquadIssueWorklogCache> entries) {
        for (SquadIssueWorklogCache w : entries) {
            w.setSquadId(squadId);
            if (w.getDbId() == null || w.getDbId().isBlank()) {
                w.setDbId(squadId + "_" + w.getJiraKey());
            }
        }
        return worklogCacheRepository.saveAll(entries);
    }

    @Transactional
    public void deleteWorklogCacheEntry(String squadId, String jiraKey) {
        worklogCacheRepository.deleteById(squadId + "_" + jiraKey);
    }
}
