package com.agilespace.backend.service;

import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.UserProjectAccessDto;
import com.agilespace.backend.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SquadAudienceService - squads acessíveis e membros por squad")
class SquadAudienceServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private ProjectMemberRoleRepository projectMemberRoleRepository;
    @Mock private SquadMemberRepository squadMemberRepository;
    @Mock private UserProjectResolverService resolver;
    @Mock private SquadRepository squadRepository;
    @Mock private ProjectConfigRepository projectConfigRepository;
    @InjectMocks private SquadAudienceService service;

    private static UserProjectAccessDto.ProjectAccessItem project(String id, String roleKey, boolean leadership) {
        return UserProjectAccessDto.ProjectAccessItem.builder().projectId(id).roleKey(roleKey).isLeadership(leadership).build();
    }

    @Test
    @DisplayName("Squad da sessão vem primeiro; projetos e roster entram sem duplicar (sem caixa)")
    void contextOrdersCurrentSquadFirst() {
        User ana = User.builder().id("ana").email("ana@totvs.com.br").defaultProjectId("SQB").squadId("sqb").build();
        when(userRepository.findById("ana")).thenReturn(Optional.of(ana));
        when(resolver.resolveUserAccess(ana)).thenReturn(UserProjectAccessDto.builder()
                .projects(List.of(project("SQA", "DEV_TEAM", false), project("SQB", "DEV_TEAM", false))).build());
        when(squadMemberRepository.findByClaimedByUid("ana")).thenReturn(List.of(SquadMember.builder().squadId("SQC").build()));
        when(squadMemberRepository.findByUserIdentifier("ana@totvs.com.br")).thenReturn(List.of(
                SquadMember.builder().squadId("SQD").email("ANA@totvs.com.br").build(),
                SquadMember.builder().squadId("SQX").displayName("ana@totvs.com.br").build()));

        SquadAudienceService.UserContext ctx = service.contextFor("ana", "MEMBER").orElseThrow();

        assertEquals("SQB", ctx.currentSquadId());
        assertEquals(List.of("SQB", "SQA", "SQC", "SQD"), ctx.squadIds());
        assertTrue(ctx.roleKeys().containsAll(Set.of("MEMBER", "DEV_TEAM")));
        assertFalse(ctx.leadership());
        assertFalse(ctx.multiSquad());
    }

    @Test
    @DisplayName("Liderança com mais de uma squad = visão multi-squad")
    void leadershipWithManySquadsIsMultiSquad() {
        User am = User.builder().id("am").defaultProjectId("SQA").build();
        when(userRepository.findById("am")).thenReturn(Optional.of(am));
        when(resolver.resolveUserAccess(am)).thenReturn(UserProjectAccessDto.builder()
                .projects(List.of(project("SQA", "AGILE_MASTER", true), project("SQB", "AGILE_MASTER", true))).build());

        SquadAudienceService.UserContext ctx = service.contextFor("am", "MEMBER").orElseThrow();

        assertTrue(ctx.leadership());
        assertTrue(ctx.multiSquad());
        assertTrue(ctx.roleKeys().contains("AGILE_MASTER"));
    }

    @Test
    @DisplayName("Usuário inexistente: sem contexto")
    void unknownUser() {
        when(userRepository.findById("x")).thenReturn(Optional.empty());
        assertTrue(service.contextFor("x", null).isEmpty());
        assertTrue(service.contextFor(null, null).isEmpty());
    }

    @Test
    @DisplayName("Membros: sessão, papel em projeto (id/e-mail/Jira) e roster; consultas em lote")
    void memberUserIdsResolvesAllSources() {
        when(userRepository.findActiveBySquadIds(Set.of("SQA", "SQB"))).thenReturn(List.of(
                User.builder().id("u1").squadId("sqa").build()));
        when(projectMemberRoleRepository.findByProjectIdsUpper(Set.of("SQA", "SQB"))).thenReturn(List.of(
                ProjectMemberRole.builder().projectId("SQB").userId("u2").build(),
                ProjectMemberRole.builder().projectId("SQB").email("Tri@x.com").build()));
        when(squadMemberRepository.findBySquadIdsUpper(Set.of("SQA", "SQB"))).thenReturn(List.of(
                SquadMember.builder().squadId("SQA").jiraAccountId("jira-4").build()));
        when(userRepository.findActiveByIds(Set.of("u2"))).thenReturn(List.of(User.builder().id("u2").build()));
        when(userRepository.findActiveByEmails(Set.of("tri@x.com"))).thenReturn(List.of(User.builder().id("u3").email("tri@x.com").build()));
        when(userRepository.findActiveByJiraAccountIds(Set.of("jira-4"))).thenReturn(List.of(User.builder().id("u4").jiraAccountId("jira-4").build()));

        Map<String, Set<String>> out = service.memberUserIds(List.of("sqa", "SQB"));

        assertEquals(Set.of("u1", "u4"), out.get("SQA"));
        assertEquals(Set.of("u2", "u3"), out.get("SQB"));
    }

    @Test
    @DisplayName("Sem squads: nenhuma consulta")
    void emptySquadsShortCircuit() {
        assertTrue(service.memberUserIds(List.of()).isEmpty());
        verifyNoInteractions(userRepository, projectMemberRoleRepository, squadMemberRepository);
    }

    @Test
    @DisplayName("Papéis: UserRole e roleKey de project_member_roles")
    void userIdsWithRoles() {
        when(userRepository.findActiveIdsByRoles(Set.of("ADMIN", "AGILE_MASTER"))).thenReturn(List.of("adm"));
        when(projectMemberRoleRepository.findByRoleKeysUpper(Set.of("ADMIN", "AGILE_MASTER"))).thenReturn(List.of(
                ProjectMemberRole.builder().projectId("SQA").roleKey("AGILE_MASTER").userId("am").build()));
        when(userRepository.findActiveByIds(Set.of("am"))).thenReturn(List.of(User.builder().id("am").build()));

        assertEquals(Set.of("adm", "am"), service.userIdsWithRoles(List.of("admin", "Agile_Master")));
    }
}
