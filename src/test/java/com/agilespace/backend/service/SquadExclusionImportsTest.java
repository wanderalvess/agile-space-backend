package com.agilespace.backend.service;

import com.agilespace.backend.domain.ProjectConfig;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.SquadMemberExclusion;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.JiraConfirmSyncRequest;
import com.agilespace.backend.dto.JiraMemberCandidateDto;
import com.agilespace.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Todo caminho automático que põe gente no time respeita quem a liderança removeu à mão. */
@ExtendWith(MockitoExtension.class)
@DisplayName("Exclusões do time - importações e joins não recolocam quem foi removido à mão")
class SquadExclusionImportsTest {

    @Mock private SquadMemberExclusionRepository exclusionRepository;
    @Mock private ProjectConfigRepository projectConfigRepository;
    @Mock private ProjectMemberRoleRepository projectMemberRoleRepository;
    @Mock private UserRepository userRepository;
    @Mock private JiraAdminService jiraAdminService;
    @Mock private JiraService jiraService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus txStatus;

    private SquadMemberExclusions exclusions;

    @BeforeEach
    void setUp() {
        exclusions = new SquadMemberExclusions(exclusionRepository);
        lenient().when(exclusionRepository.findBySquadId("DDW")).thenReturn(List.of(
                SquadMemberExclusion.builder().squadId("DDW").jiraAccountId("saiu").email("Saiu@X.com").build()));
        lenient().when(transactionManager.getTransaction(any())).thenReturn(txStatus);
    }

    private ProjectMemberRole row(String acc, String email) {
        return ProjectMemberRole.builder().projectId("DDW").jiraAccountId(acc).displayName(acc).email(email)
                .roleName("Developer").roleKey("DEVELOPER").build();
    }

    @Test
    @DisplayName("Reimportação Profields: quem foi removido à mão (por conta do Jira ou e-mail) não volta")
    void profieldsReimportSkipsRemoved() {
        JiraProfieldsService service = new JiraProfieldsService(projectConfigRepository, projectMemberRoleRepository, userRepository,
                jiraAdminService, jiraService, transactionManager, exclusions);
        when(projectConfigRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(projectMemberRoleRepository.findByProjectId("DDW")).thenReturn(List.of());
        when(projectMemberRoleRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        List<ProjectMemberRole> members = new ArrayList<>(List.of(row("saiu", "outro@x.com"), row("outra-conta", "saiu@x.com"), row("fica", "fica@x.com")));

        ReflectionTestUtils.invokeMethod(service, "persistImport", ProjectConfig.builder().id("DDW").name("DDW").build(), members, members);

        ArgumentCaptor<List<ProjectMemberRole>> saved = ArgumentCaptor.forClass(List.class);
        verify(projectMemberRoleRepository).saveAll(saved.capture());
        assertEquals(List.of("fica"), saved.getValue().stream().map(ProjectMemberRole::getJiraAccountId).toList());
    }

    @Test
    @DisplayName("Entrar no time por conta própria (join) é recusado para quem foi removido; quem não foi entra")
    void selfJoinBlockedForRemoved() {
        JiraProfieldsService service = new JiraProfieldsService(projectConfigRepository, projectMemberRoleRepository, userRepository,
                jiraAdminService, jiraService, transactionManager, exclusions);
        when(projectConfigRepository.existsById("DDW")).thenReturn(true);
        User removed = User.builder().id("u1").name("Saiu").email("saiu@x.com").jiraAccountId("saiu").build();

        assertThrows(ResponseStatusException.class, () -> service.joinProject("DDW", "Developer", removed));
        verify(projectMemberRoleRepository, never()).save(any());

        when(projectMemberRoleRepository.findByProjectId("DDW")).thenReturn(List.of());
        when(projectMemberRoleRepository.save(any(ProjectMemberRole.class))).thenAnswer(i -> i.getArgument(0));
        service.joinProject("DDW", "Developer", User.builder().id("u2").name("Outro").email("outro@x.com").jiraAccountId("outro").build());
        verify(projectMemberRoleRepository).save(any(ProjectMemberRole.class));
    }

    @Test
    @DisplayName("Importação de quadro (JiraAdminService): membro removido à mão é pulado")
    void boardImportSkipsRemoved() {
        SquadRepository squadRepository = mock(SquadRepository.class);
        SquadMemberRepository memberRepository = mock(SquadMemberRepository.class);
        SquadMetricsRollupRepository rollupRepository = mock(SquadMetricsRollupRepository.class);
        JiraAdminService admin = new JiraAdminService();
        ReflectionTestUtils.setField(admin, "squadRepository", squadRepository);
        ReflectionTestUtils.setField(admin, "squadMemberRepository", memberRepository);
        ReflectionTestUtils.setField(admin, "userRepository", userRepository);
        ReflectionTestUtils.setField(admin, "squadMetricsRollupRepository", rollupRepository);
        ReflectionTestUtils.setField(admin, "squadMemberExclusions", exclusions);
        when(squadRepository.findById("DDW")).thenReturn(Optional.empty());
        when(squadRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(rollupRepository.findById("DDW")).thenReturn(Optional.empty());
        when(rollupRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(memberRepository.findById(any())).thenReturn(Optional.empty());
        when(memberRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        JiraConfirmSyncRequest request = JiraConfirmSyncRequest.builder().squadId("DDW").squadName("DDW")
                .jiraDomain("empresa.atlassian.net").replaceExisting(false).syncUsers(false)
                .members(List.of(candidate("saiu", "x@y.com"), candidate("fica", "fica@x.com"))).build();

        admin.confirmSync(request);

        ArgumentCaptor<SquadMember> saved = ArgumentCaptor.forClass(SquadMember.class);
        verify(memberRepository, atLeastOnce()).save(saved.capture());
        assertEquals(List.of("fica"), saved.getAllValues().stream().map(SquadMember::getJiraAccountId).distinct().toList());
    }

    private JiraMemberCandidateDto candidate(String acc, String email) {
        return JiraMemberCandidateDto.builder().jiraAccountId(acc).displayName(acc).email(email).role("Developer")
                .score(50).selected(true).capacityHoursPerDay(6.0).build();
    }

    @Test
    @DisplayName("Salvar a pessoa à mão (convite, edição, planilha) limpa a exclusão")
    void addingAgainClearsExclusion() {
        SquadMemberRepository memberRepository = mock(SquadMemberRepository.class);
        SquadService squadService = new SquadService(mock(SquadRepository.class), mock(SquadMetricsRollupRepository.class),
                mock(SquadIssueSnapshotRepository.class), memberRepository, mock(SquadMemberMetricRepository.class),
                mock(SquadDailySnapshotRepository.class), mock(SquadIssueWorklogCacheRepository.class), userRepository,
                mock(SquadPanelRepository.class), exclusionRepository);
        when(memberRepository.findBySquadIdAndJiraAccountId("DDW", "saiu")).thenReturn(Optional.empty());
        when(memberRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(userRepository.findById("saiu")).thenReturn(Optional.empty());

        squadService.saveMember("DDW", "saiu", SquadMember.builder().displayName("Saiu").build());

        verify(exclusionRepository).deleteById("DDW_saiu");
    }
}
