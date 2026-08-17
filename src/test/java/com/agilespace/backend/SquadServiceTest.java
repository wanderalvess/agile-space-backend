package com.agilespace.backend;

import com.agilespace.backend.domain.*;
import com.agilespace.backend.repository.*;
import com.agilespace.backend.service.SquadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class SquadServiceTest {

    @Mock private SquadRepository squadRepository;
    @Mock private SquadMetricsRollupRepository rollupRepository;
    @Mock private SquadIssueSnapshotRepository issueSnapshotRepository;
    @Mock private SquadMemberRepository memberRepository;
    @Mock private SquadMemberMetricRepository memberMetricRepository;
    @Mock private SquadDailySnapshotRepository dailySnapshotRepository;
    @Mock private SquadIssueWorklogCacheRepository worklogCacheRepository;

    @InjectMocks
    private SquadService service;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testGetSquadReturnsSquad() {
        Squad squad = Squad.builder().id("squad-alpha").jiraProjectKey("ALPHA").build();
        when(squadRepository.findById("squad-alpha")).thenReturn(Optional.of(squad));

        Optional<Squad> result = service.getSquad("squad-alpha");
        assertTrue(result.isPresent());
        assertEquals("ALPHA", result.get().getJiraProjectKey());
    }

    @Test
    public void testSaveSquad() {
        Squad squad = Squad.builder().build();
        when(squadRepository.save(squad)).thenReturn(squad);
        Squad saved = service.saveSquad(squad);
        assertNotNull(saved);
    }

    @Test
    public void testGetRollup() {
        SquadMetricsRollup rollup = new SquadMetricsRollup();
        when(rollupRepository.findById("s1")).thenReturn(Optional.of(rollup));
        Optional<SquadMetricsRollup> result = service.getRollup("s1");
        assertTrue(result.isPresent());
    }

    @Test
    public void testSaveRollup() {
        SquadMetricsRollup rollup = new SquadMetricsRollup();
        rollup.setSquadId("s1");
        when(rollupRepository.save(rollup)).thenReturn(rollup);
        SquadMetricsRollup saved = service.saveRollup(rollup);
        assertEquals("s1", saved.getSquadId());
    }

    @Test
    public void testGetIssuesWithSprintId() {
        when(issueSnapshotRepository.findBySquadIdAndSprintId("s1", "sp1")).thenReturn(Arrays.asList(new SquadIssueSnapshot()));
        List<SquadIssueSnapshot> result = service.getIssues("s1", "sp1");
        assertEquals(1, result.size());
    }

    @Test
    public void testGetIssuesWithoutSprintId() {
        when(issueSnapshotRepository.findBySquadId("s1")).thenReturn(Arrays.asList(new SquadIssueSnapshot()));
        List<SquadIssueSnapshot> result = service.getIssues("s1", null);
        assertEquals(1, result.size());
    }

    @Test
    public void testGetIssuesByAssignee() {
        when(issueSnapshotRepository.findBySquadIdAndAssigneeId("s1", "a1")).thenReturn(Arrays.asList(new SquadIssueSnapshot()));
        List<SquadIssueSnapshot> result = service.getIssuesByAssignee("s1", "a1");
        assertEquals(1, result.size());
    }

    @Test
    public void testGetIssueByKey() {
        SquadIssueSnapshot snap = new SquadIssueSnapshot();
        when(issueSnapshotRepository.findBySquadIdAndJiraKey("s1", "k1")).thenReturn(Optional.of(snap));
        Optional<SquadIssueSnapshot> result = service.getIssueByKey("s1", "k1");
        assertTrue(result.isPresent());
    }

    @Test
    public void testBatchUpsertIssuesGeneratesDbId() {
        SquadIssueSnapshot snap1 = SquadIssueSnapshot.builder().jiraKey("JIRA-1").status("To Do").build();
        List<SquadIssueSnapshot> list = Arrays.asList(snap1);

        when(issueSnapshotRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<SquadIssueSnapshot> result = service.batchUpsertIssues("squad-alpha", list);
        assertEquals(1, result.size());
        assertEquals("squad-alpha_JIRA-1", result.get(0).getDbId());
        assertEquals("squad-alpha", result.get(0).getSquadId());
    }

    @Test
    public void testBatchDeleteIssues() {
        service.batchDeleteIssues("s1", Arrays.asList("k1"));
        verify(issueSnapshotRepository).deleteBySquadIdAndJiraKeyIn("s1", Arrays.asList("k1"));
    }

    @Test
    public void testGetMembers() {
        when(memberRepository.findBySquadIdOrderByDisplayNameAsc("s1")).thenReturn(Arrays.asList(new SquadMember()));
        List<SquadMember> result = service.getMembers("s1");
        assertEquals(1, result.size());
    }

    @Test
    public void testSaveMemberCreatesCorrectDbId() {
        SquadMember member = SquadMember.builder().displayName("Alves").build();
        when(memberRepository.findBySquadIdAndJiraAccountId("squad-alpha", "acc-456")).thenReturn(Optional.empty());
        when(memberRepository.save(any(SquadMember.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SquadMember saved = service.saveMember("squad-alpha", "acc-456", member);
        assertEquals("squad-alpha_acc-456", saved.getDbId());
        assertEquals("squad-alpha", saved.getSquadId());
        assertEquals("acc-456", saved.getJiraAccountId());
    }

    @Test
    public void testSaveMemberMergesExistingData() {
        SquadMember existing = SquadMember.builder().displayName("Old Name").capacityHoursPerDay(8.0).build();
        SquadMember update = SquadMember.builder().build(); // null fields should not overwrite

        when(memberRepository.findBySquadIdAndJiraAccountId("s1", "a1")).thenReturn(Optional.of(existing));
        when(memberRepository.save(any(SquadMember.class))).thenAnswer(i -> i.getArgument(0));

        SquadMember saved = service.saveMember("s1", "a1", update);
        assertEquals("Old Name", saved.getDisplayName());
        assertEquals(8.0, saved.getCapacityHoursPerDay());
    }

    @Test
    public void testBatchUpsertMembers() {
        SquadMember m1 = SquadMember.builder().jiraAccountId("a1").build();
        when(memberRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

        List<SquadMember> result = service.batchUpsertMembers("s1", Arrays.asList(m1));
        assertEquals("s1_a1", result.get(0).getDbId());
    }

    @Test
    public void testGetMemberMetrics() {
        when(memberMetricRepository.findBySquadId("s1")).thenReturn(Arrays.asList(new SquadMemberMetric()));
        List<SquadMemberMetric> result = service.getMemberMetrics("s1");
        assertEquals(1, result.size());
    }

    @Test
    public void testBatchUpsertMemberMetrics() {
        SquadMemberMetric m1 = SquadMemberMetric.builder().assigneeId("a1").build();
        when(memberMetricRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

        List<SquadMemberMetric> result = service.batchUpsertMemberMetrics("s1", Arrays.asList(m1));
        assertEquals("s1_a1", result.get(0).getDbId());
        verify(memberMetricRepository).deleteBySquadId("s1");
    }

    @Test
    public void testGetDailySnapshotsWithSince() {
        when(dailySnapshotRepository.findBySquadIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc("s1", "2026-08-01"))
                .thenReturn(Arrays.asList(new SquadDailySnapshot()));
        List<SquadDailySnapshot> result = service.getDailySnapshots("s1", "2026-08-01");
        assertEquals(1, result.size());
    }

    @Test
    public void testGetDailySnapshotsWithoutSince() {
        when(dailySnapshotRepository.findBySquadIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc("s1", "0000-00-00"))
                .thenReturn(Arrays.asList(new SquadDailySnapshot()));
        List<SquadDailySnapshot> result = service.getDailySnapshots("s1", null);
        assertEquals(1, result.size());
    }

    @Test
    public void testBatchUpsertDailySnapshots() {
        SquadDailySnapshot s1 = SquadDailySnapshot.builder().snapshotDate("2026-08-01").build();
        when(dailySnapshotRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

        List<SquadDailySnapshot> result = service.batchUpsertDailySnapshots("s1", Arrays.asList(s1));
        assertEquals("s1_2026-08-01", result.get(0).getDbId());
    }

    @Test
    public void testGetWorklogCacheWithSprintId() {
        when(worklogCacheRepository.findBySquadIdAndSprintId("s1", "sp1")).thenReturn(Arrays.asList(new SquadIssueWorklogCache()));
        List<SquadIssueWorklogCache> result = service.getWorklogCache("s1", "sp1");
        assertEquals(1, result.size());
    }

    @Test
    public void testGetWorklogCacheWithoutSprintId() {
        when(worklogCacheRepository.findBySquadId("s1")).thenReturn(Arrays.asList(new SquadIssueWorklogCache()));
        List<SquadIssueWorklogCache> result = service.getWorklogCache("s1", null);
        assertEquals(1, result.size());
    }

    @Test
    public void testBatchUpsertWorklogCache() {
        SquadIssueWorklogCache w1 = SquadIssueWorklogCache.builder().jiraKey("k1").build();
        when(worklogCacheRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

        List<SquadIssueWorklogCache> result = service.batchUpsertWorklogCache("s1", Arrays.asList(w1));
        assertEquals("s1_k1", result.get(0).getDbId());
    }

    @Test
    public void testDeleteWorklogCacheEntry() {
        service.deleteWorklogCacheEntry("s1", "k1");
        verify(worklogCacheRepository).deleteById("s1_k1");
    }
}
