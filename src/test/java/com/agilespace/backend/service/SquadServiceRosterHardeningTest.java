package com.agilespace.backend.service;

import com.agilespace.backend.domain.*;
import com.agilespace.backend.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SquadService - ids de linha por squad, roster em lote e claim")
class SquadServiceRosterHardeningTest {

    @Mock private SquadRepository squadRepository;
    @Mock private SquadMetricsRollupRepository rollupRepository;
    @Mock private SquadIssueSnapshotRepository issueSnapshotRepository;
    @Mock private SquadMemberRepository memberRepository;
    @Mock private SquadMemberMetricRepository memberMetricRepository;
    @Mock private SquadDailySnapshotRepository dailySnapshotRepository;
    @Mock private SquadIssueWorklogCacheRepository worklogCacheRepository;
    @Mock private UserRepository userRepository;
    @Mock private SquadPanelRepository panelRepository;

    @InjectMocks
    private SquadService service;

    @Test
    @DisplayName("Id de linha apontando para OUTRA squad é descartado (não sobrescreve a linha alheia)")
    void foreignDbIdIsReplaced() {
        assertEquals("SQ1_ABC-1", SquadService.ownedDbId("SQ1", "SQ2_ABC-1", "ABC-1"));
        assertEquals("SQ1_ABC-1", SquadService.ownedDbId("SQ1", null, "ABC-1"));
        assertEquals("SQ1_ABC-1", SquadService.ownedDbId("SQ1", "SQ1_null", "ABC-1"));
        assertEquals("SQ1_legado-x", SquadService.ownedDbId("SQ1", "SQ1_legado-x", "ABC-1"));
    }

    @Test
    @DisplayName("Issues em lote: id vindo de outra squad não escapa")
    void batchIssuesNeverWritesIntoAnotherSquadRow() {
        SquadIssueSnapshot snap = SquadIssueSnapshot.builder().dbId("SQ2_ABC-1").jiraKey("ABC-1").build();
        when(issueSnapshotRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        service.batchUpsertIssues("SQ1", List.of(snap));

        assertEquals("SQ1", snap.getSquadId());
        assertEquals("SQ1_ABC-1", snap.getDbId());
    }

    @Test
    @DisplayName("Rollup com id de outra squad volta para {squad}_{sprint}")
    void rollupDbIdIsOwned() {
        SquadMetricsRollup r = new SquadMetricsRollup();
        r.setSquadId("SQ1");
        r.setSprintId("42");
        r.setDbId("SQ2_42");
        when(rollupRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        assertEquals("SQ1_42", service.saveRollup(r).getDbId());
    }

    @Test
    @DisplayName("Roster em lote: campo ausente preserva o salvo e o vínculo de conta nunca é trocado")
    void batchMembersMergesAndKeepsClaim() {
        SquadMember stored = SquadMember.builder().dbId("SQ1_acc-1").squadId("SQ1").jiraAccountId("acc-1")
                .displayName("Ana").email("ana@x.com").role("Developer").capacityHoursPerDay(6.0).claimedByUid("user-ana").build();
        when(memberRepository.findBySquadIdOrderByDisplayNameAsc("SQ1")).thenReturn(List.of(stored));
        when(memberRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        SquadMember incoming = SquadMember.builder().jiraAccountId("acc-1").capacityHoursPerDay(4.0).claimedByUid("invasor").build();

        List<SquadMember> saved = service.batchUpsertMembers("SQ1", List.of(incoming));

        assertEquals(1, saved.size());
        assertEquals("SQ1_acc-1", saved.get(0).getDbId());
        assertEquals("Ana", saved.get(0).getDisplayName());
        assertEquals("ana@x.com", saved.get(0).getEmail());
        assertEquals(4.0, saved.get(0).getCapacityHoursPerDay());
        assertEquals("user-ana", saved.get(0).getClaimedByUid());
    }

    @Test
    @DisplayName("Roster em lote: linha nova nasce sem vínculo e linha sem conta Jira é ignorada")
    void batchMembersNewRowsAndBlankIds() {
        when(memberRepository.findBySquadIdOrderByDisplayNameAsc("SQ1")).thenReturn(List.of());
        when(memberRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        SquadMember fresh = SquadMember.builder().jiraAccountId("acc-2").dbId("SQ9_acc-2").claimedByUid("alguem").build();
        SquadMember blank = SquadMember.builder().jiraAccountId(" ").build();

        List<SquadMember> saved = service.batchUpsertMembers("SQ1", List.of(fresh, blank));

        assertEquals(1, saved.size());
        assertEquals("SQ1_acc-2", saved.get(0).getDbId());
        assertNull(saved.get(0).getClaimedByUid());
    }

    @Test
    @DisplayName("Claim: liga a conta a uma linha livre sem mexer em nome, papel ou capacidade")
    void claimFreeRow() {
        SquadMember row = SquadMember.builder().dbId("SQ1_acc-1").squadId("SQ1").jiraAccountId("acc-1").displayName("Ana").role("QA").build();
        when(memberRepository.findBySquadIdAndJiraAccountId("SQ1", "acc-1")).thenReturn(Optional.of(row));
        when(memberRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        SquadMember claimed = service.claimMember("SQ1", "acc-1", "user-ana");

        assertEquals("user-ana", claimed.getClaimedByUid());
        assertEquals("Ana", claimed.getDisplayName());
        assertEquals("QA", claimed.getRole());
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("Claim de linha já vinculada a outra conta dá 409; linha inexistente dá 404")
    void claimConflictsAndMissing() {
        SquadMember taken = SquadMember.builder().jiraAccountId("acc-1").claimedByUid("outro").build();
        when(memberRepository.findBySquadIdAndJiraAccountId("SQ1", "acc-1")).thenReturn(Optional.of(taken));
        when(memberRepository.findBySquadIdAndJiraAccountId("SQ1", "nada")).thenReturn(Optional.empty());

        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> service.claimMember("SQ1", "acc-1", "eu")).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, () -> service.claimMember("SQ1", "nada", "eu")).getStatusCode());
        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("Claim repetido pela mesma conta é aceito (idempotente)")
    void claimIsIdempotentForSameAccount() {
        SquadMember mine = SquadMember.builder().jiraAccountId("acc-1").claimedByUid("EU").build();
        when(memberRepository.findBySquadIdAndJiraAccountId("SQ1", "acc-1")).thenReturn(Optional.of(mine));
        when(memberRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        assertEquals("eu", service.claimMember("SQ1", "acc-1", "eu").getClaimedByUid());
    }

    @Test
    @DisplayName("syncOwnerUserId vazio desliga o sync agendado (volta a null) em vez de gravar texto vazio")
    void blankSyncOwnerTurnsScheduleOff() {
        Squad stored = Squad.builder().id("SQ1").name("SQ1").syncOwnerUserId("u1").build();
        when(squadRepository.findById("SQ1")).thenReturn(Optional.of(stored));
        when(squadRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.saveSquad(Squad.builder().id("SQ1").syncOwnerUserId("").build());

        ArgumentCaptor<Squad> captor = ArgumentCaptor.forClass(Squad.class);
        verify(squadRepository).save(captor.capture());
        assertNull(captor.getValue().getSyncOwnerUserId());
    }
}
