package com.agilespace.backend;

import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.SquadMetricsRollup;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.JiraConfirmSyncRequest;
import com.agilespace.backend.dto.JiraMemberCandidateDto;
import com.agilespace.backend.dto.JiraSyncResult;
import com.agilespace.backend.repository.SquadMemberRepository;
import com.agilespace.backend.repository.SquadMetricsRollupRepository;
import com.agilespace.backend.repository.SquadRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.service.JiraAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class JiraAdminServiceTest {

    @Mock private SquadRepository squadRepository;
    @Mock private SquadMemberRepository squadMemberRepository;
    @Mock private UserRepository userRepository;
    @Mock private SquadMetricsRollupRepository squadMetricsRollupRepository;

    @InjectMocks
    private JiraAdminService service;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    private JiraMemberCandidateDto candidate(String accountId, String email, boolean selected) {
        return JiraMemberCandidateDto.builder()
                .jiraAccountId(accountId)
                .displayName("User " + accountId)
                .email(email)
                .role("Developer")
                .score(50)
                .selected(selected)
                .capacityHoursPerDay(6.0)
                .build();
    }

    @Test
    public void confirmSyncCreatesNewSquadAndDefaultRollup() {
        JiraConfirmSyncRequest request = JiraConfirmSyncRequest.builder()
                .squadId("proj1")
                .squadName("Projeto Um")
                .jiraDomain("empresa.atlassian.net")
                .replaceExisting(false)
                .syncUsers(false)
                .members(Collections.emptyList())
                .build();

        when(squadRepository.findById("PROJ1")).thenReturn(Optional.empty());
        when(squadRepository.save(any(Squad.class))).thenAnswer(i -> i.getArgument(0));
        when(squadMetricsRollupRepository.findById("PROJ1")).thenReturn(Optional.empty());
        when(squadMetricsRollupRepository.save(any(SquadMetricsRollup.class))).thenAnswer(i -> i.getArgument(0));

        JiraSyncResult result = service.confirmSync(request);

        ArgumentCaptor<Squad> squadCaptor = ArgumentCaptor.forClass(Squad.class);
        verify(squadRepository).save(squadCaptor.capture());
        assertEquals("PROJ1", squadCaptor.getValue().getId());
        assertEquals("Projeto Um", squadCaptor.getValue().getName());
        assertEquals("success", squadCaptor.getValue().getLastSyncStatus());

        ArgumentCaptor<SquadMetricsRollup> rollupCaptor = ArgumentCaptor.forClass(SquadMetricsRollup.class);
        verify(squadMetricsRollupRepository).save(rollupCaptor.capture());
        assertEquals(0, rollupCaptor.getValue().getTotalIssues());
        assertEquals(10, rollupCaptor.getValue().getWorkdaysTotal());

        assertEquals("PROJ1", result.getSquadId());
        assertEquals(0, result.getMembersFound());
    }

    @Test
    public void confirmSyncPreservesExistingRollupValues() {
        JiraConfirmSyncRequest request = JiraConfirmSyncRequest.builder()
                .squadId("proj1")
                .replaceExisting(false)
                .members(Collections.emptyList())
                .build();

        SquadMetricsRollup existingRollup = SquadMetricsRollup.builder()
                .squadId("PROJ1")
                .sprintName("Sprint 5")
                .totalIssues(42)
                .build();

        when(squadRepository.findById("PROJ1")).thenReturn(Optional.empty());
        when(squadRepository.save(any(Squad.class))).thenAnswer(i -> i.getArgument(0));
        when(squadMetricsRollupRepository.findById("PROJ1")).thenReturn(Optional.of(existingRollup));
        when(squadMetricsRollupRepository.save(any(SquadMetricsRollup.class))).thenAnswer(i -> i.getArgument(0));

        service.confirmSync(request);

        ArgumentCaptor<SquadMetricsRollup> rollupCaptor = ArgumentCaptor.forClass(SquadMetricsRollup.class);
        verify(squadMetricsRollupRepository).save(rollupCaptor.capture());
        assertEquals("Sprint 5", rollupCaptor.getValue().getSprintName());
        assertEquals(42, rollupCaptor.getValue().getTotalIssues());
    }

    @Test
    public void confirmSyncSavesOnlySelectedMembers() {
        JiraConfirmSyncRequest request = JiraConfirmSyncRequest.builder()
                .squadId("proj1")
                .replaceExisting(false)
                .syncUsers(false)
                .members(Arrays.asList(
                        candidate("acc1", "acc1@empresa.com", true),
                        candidate("acc2", "acc2@empresa.com", false)
                ))
                .build();

        when(squadRepository.findById("PROJ1")).thenReturn(Optional.empty());
        when(squadRepository.save(any(Squad.class))).thenAnswer(i -> i.getArgument(0));
        when(squadMetricsRollupRepository.findById("PROJ1")).thenReturn(Optional.empty());
        when(squadMetricsRollupRepository.save(any(SquadMetricsRollup.class))).thenAnswer(i -> i.getArgument(0));
        when(squadMemberRepository.findById(anyString())).thenReturn(Optional.empty());
        when(squadMemberRepository.save(any(SquadMember.class))).thenAnswer(i -> i.getArgument(0));

        JiraSyncResult result = service.confirmSync(request);

        assertEquals(1, result.getMembersFound());
        verify(squadMemberRepository, times(1)).save(any(SquadMember.class));
    }

    @Test
    public void confirmSyncReplaceExistingRemovesUnapprovedMembersAndUnlinksUser() {
        JiraConfirmSyncRequest request = JiraConfirmSyncRequest.builder()
                .squadId("proj1")
                .replaceExisting(true)
                .syncUsers(true)
                .members(Arrays.asList(candidate("acc1", "acc1@empresa.com", true)))
                .build();

        SquadMember staying = SquadMember.builder().dbId("PROJ1_acc1").squadId("PROJ1").jiraAccountId("acc1").build();
        SquadMember leaving = SquadMember.builder().dbId("PROJ1_acc2").squadId("PROJ1").jiraAccountId("acc2").displayName("Saindo").build();

        User leavingUser = User.builder().id("acc2").squadId("PROJ1").email("acc2@empresa.com").build();

        when(squadRepository.findById("PROJ1")).thenReturn(Optional.empty());
        when(squadRepository.save(any(Squad.class))).thenAnswer(i -> i.getArgument(0));
        when(squadMetricsRollupRepository.findById("PROJ1")).thenReturn(Optional.empty());
        when(squadMetricsRollupRepository.save(any(SquadMetricsRollup.class))).thenAnswer(i -> i.getArgument(0));
        when(squadMemberRepository.findBySquadIdOrderByDisplayNameAsc("PROJ1")).thenReturn(Arrays.asList(staying, leaving));
        when(userRepository.findByJiraAccountId("acc2")).thenReturn(Optional.of(leavingUser));
        when(squadMemberRepository.findById(anyString())).thenReturn(Optional.empty());
        when(squadMemberRepository.save(any(SquadMember.class))).thenAnswer(i -> i.getArgument(0));
        when(userRepository.findById("acc1")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("acc1@empresa.com")).thenReturn(Optional.empty());
        when(userRepository.findByJiraAccountId("acc1")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        service.confirmSync(request);

        verify(squadMemberRepository).delete(leaving);
        verify(squadMemberRepository, never()).delete(staying);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, atLeastOnce()).save(userCaptor.capture());
        boolean unlinkedLeavingUser = userCaptor.getAllValues().stream()
                .anyMatch(u -> "acc2".equals(u.getId()) && u.getSquadId() == null);
        assertTrue(unlinkedLeavingUser);
    }

    @Test
    public void confirmSyncCreatesNewUserWhenSyncUsersEnabled() {
        JiraConfirmSyncRequest request = JiraConfirmSyncRequest.builder()
                .squadId("proj1")
                .replaceExisting(false)
                .syncUsers(true)
                .members(Arrays.asList(candidate("acc1", null, true)))
                .build();

        when(squadRepository.findById("PROJ1")).thenReturn(Optional.empty());
        when(squadRepository.save(any(Squad.class))).thenAnswer(i -> i.getArgument(0));
        when(squadMetricsRollupRepository.findById("PROJ1")).thenReturn(Optional.empty());
        when(squadMetricsRollupRepository.save(any(SquadMetricsRollup.class))).thenAnswer(i -> i.getArgument(0));
        when(squadMemberRepository.findById(anyString())).thenReturn(Optional.empty());
        when(squadMemberRepository.save(any(SquadMember.class))).thenAnswer(i -> i.getArgument(0));
        when(userRepository.findById("acc1")).thenReturn(Optional.empty());
        when(userRepository.findByJiraAccountId("acc1")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        service.confirmSync(request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User created = userCaptor.getValue();
        assertEquals("acc1", created.getId());
        assertEquals("acc1@empresa.com", created.getEmail());
        assertEquals("PROJ1", created.getSquadId());
        assertEquals(6, created.getDailyHours());
    }

    @Test
    public void confirmSyncUpdatesExistingUserRoleAndCapacity() {
        JiraConfirmSyncRequest request = JiraConfirmSyncRequest.builder()
                .squadId("proj1")
                .replaceExisting(false)
                .syncUsers(true)
                .members(Arrays.asList(candidate("acc1", "acc1@empresa.com", true)))
                .build();

        User existingUser = User.builder().id("acc1").email("acc1@empresa.com").role("Old Role").dailyHours(8).build();

        when(squadRepository.findById("PROJ1")).thenReturn(Optional.empty());
        when(squadRepository.save(any(Squad.class))).thenAnswer(i -> i.getArgument(0));
        when(squadMetricsRollupRepository.findById("PROJ1")).thenReturn(Optional.empty());
        when(squadMetricsRollupRepository.save(any(SquadMetricsRollup.class))).thenAnswer(i -> i.getArgument(0));
        when(squadMemberRepository.findById(anyString())).thenReturn(Optional.empty());
        when(squadMemberRepository.save(any(SquadMember.class))).thenAnswer(i -> i.getArgument(0));
        when(userRepository.findById("acc1")).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        service.confirmSync(request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User updated = userCaptor.getValue();
        assertEquals("Developer", updated.getRole());
        assertEquals(6, updated.getDailyHours());
        assertEquals("PROJ1", updated.getSquadId());
    }
}
