package com.agilespace.backend.service;

import com.agilespace.backend.domain.ProjectConfig;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.UserProjectAccessDto;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class UserProjectResolverServiceTest {

    @Mock
    private ProjectMemberRoleRepository projectMemberRoleRepository;

    @Mock
    private ProjectConfigRepository projectConfigRepository;

    @InjectMocks
    private UserProjectResolverService service;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    private User user(String email, String jiraAccountId) {
        return User.builder().id("u1").name("Joao Silva").email(email).jiraAccountId(jiraAccountId).build();
    }

    private ProjectMemberRole role(String id, String projectId, String roleKey, String roleName, boolean leadership) {
        ProjectMemberRole r = new ProjectMemberRole();
        r.setId(id);
        r.setProjectId(projectId);
        r.setRoleKey(roleKey);
        r.setRoleName(roleName);
        r.setLeadership(leadership);
        return r;
    }

    private ProjectConfig project(String id, String name, String segment, String tribe) {
        ProjectConfig p = new ProjectConfig();
        p.setId(id);
        p.setName(name);
        p.setSegmentName(segment);
        p.setTribeName(tribe);
        return p;
    }

    @Test
    public void testResolvesDirectRolesWithProjectMetadata() {
        when(projectMemberRoleRepository.findByEmailIgnoreCase("joao@empresa.com.br"))
                .thenReturn(List.of(role("r1", "DDWMISSI", "PRODUCT_OWNER", "Product Owner", true)));
        when(projectConfigRepository.findById("DDWMISSI"))
                .thenReturn(Optional.of(project("DDWMISSI", "Missao Digital", "Segmento X", "Tribo Y")));

        UserProjectAccessDto access = service.resolveUserAccess(user("joao@empresa.com.br", null));

        assertEquals(1, access.getProjects().size());
        UserProjectAccessDto.ProjectAccessItem item = access.getProjects().get(0);
        assertEquals("Missao Digital", item.getProjectName());
        assertEquals("Segmento X", item.getSegmentName());
        assertEquals("Product Owner", item.getRoleName());
        assertTrue(item.isDirectAssignment());
        assertTrue(item.isLeadership());
        assertFalse(access.isTransversalLeader());
    }

    @Test
    public void testFallsBackToProjectIdWhenProjectConfigMissing() {
        when(projectMemberRoleRepository.findByEmailIgnoreCase(anyString()))
                .thenReturn(List.of(role("r1", "SEM-CONFIG", "DEV_TEAM", "Dev Team", false)));
        when(projectConfigRepository.findById("SEM-CONFIG")).thenReturn(Optional.empty());

        UserProjectAccessDto access = service.resolveUserAccess(user("joao@empresa.com.br", null));

        assertEquals("SEM-CONFIG", access.getProjects().get(0).getProjectName());
        assertEquals("", access.getProjects().get(0).getSegmentName());
    }

    @Test
    public void testMergesRolesFoundByJiraAccountIdWithoutDuplicating() {
        ProjectMemberRole byEmail = role("r1", "DDWMISSI", "DEV_TEAM", "Dev Team", false);
        ProjectMemberRole byJiraSame = role("r1", "DDWMISSI", "DEV_TEAM", "Dev Team", false);
        ProjectMemberRole byJiraOther = role("r2", "OUTRO", "DEV_TEAM", "Dev Team", false);

        when(projectMemberRoleRepository.findByEmailIgnoreCase("joao@empresa.com.br")).thenReturn(List.of(byEmail));
        when(projectMemberRoleRepository.findByJiraAccountId("conta-jira")).thenReturn(List.of(byJiraSame, byJiraOther));
        when(projectConfigRepository.findById(anyString())).thenReturn(Optional.empty());

        UserProjectAccessDto access = service.resolveUserAccess(user("joao@empresa.com.br", "  conta-jira  "));

        assertEquals(2, access.getProjects().size());
        verify(projectMemberRoleRepository).findByJiraAccountId("conta-jira");
    }

    @Test
    public void testSkipsLookupsWhenEmailAndJiraAccountAreBlank() {
        UserProjectAccessDto access = service.resolveUserAccess(user("   ", "   "));

        assertTrue(access.getProjects().isEmpty());
        assertNull(access.getPrimaryProjectId());
        verify(projectMemberRoleRepository, never()).findByEmailIgnoreCase(anyString());
        verify(projectMemberRoleRepository, never()).findByJiraAccountId(anyString());
    }

    @Test
    public void testTribeLeadGetsTransversalAccessToWholeTribe() {
        when(projectMemberRoleRepository.findByEmailIgnoreCase(anyString()))
                .thenReturn(List.of(role("r1", "DDWMISSI", "TRIBE_LEAD", "Tribe Lead", true)));
        when(projectConfigRepository.findById("DDWMISSI"))
                .thenReturn(Optional.of(project("DDWMISSI", "Missao Digital", "Segmento X", "Distribuicao")));
        when(projectConfigRepository.findAll()).thenReturn(List.of(
                project("DDWMISSI", "Missao Digital", "Segmento X", "Distribuicao"),
                project("OUTRO-DA-TRIBO", "Outro Projeto", "Segmento Z", "Distribuicao"),
                project("FORA", "Fora da Tribo", "Segmento W", "Outra Tribo")));

        UserProjectAccessDto access = service.resolveUserAccess(user("lider@empresa.com.br", null));

        assertTrue(access.isTransversalLeader());
        assertEquals(2, access.getProjects().size());
        UserProjectAccessDto.ProjectAccessItem herdado = access.getProjects().stream()
                .filter(p -> "OUTRO-DA-TRIBO".equals(p.getProjectId())).findFirst().orElseThrow();
        assertEquals("TRIBE_LEADER_ACCESS", herdado.getRoleKey());
        assertFalse(herdado.isDirectAssignment());
        assertTrue(herdado.isLeadership());
    }

    @Test
    public void testAgileCoachAndPeopleLeadAlsoCountAsTransversal() {
        for (String roleKey : List.of("AGILE_COACH", "PEOPLE_LEAD")) {
            reset(projectMemberRoleRepository, projectConfigRepository);
            when(projectMemberRoleRepository.findByEmailIgnoreCase(anyString()))
                    .thenReturn(List.of(role("r1", "DDWMISSI", roleKey, roleKey, true)));
            when(projectConfigRepository.findById("DDWMISSI"))
                    .thenReturn(Optional.of(project("DDWMISSI", "Missao", "Seg", "Tribo")));
            when(projectConfigRepository.findAll()).thenReturn(List.of());

            assertTrue(service.resolveUserAccess(user("lider@empresa.com.br", null)).isTransversalLeader(),
                    "roleKey deveria ser transversal: " + roleKey);
        }
    }

    @Test
    public void testNonLeadershipRoleDoesNotExpandAccess() {
        when(projectMemberRoleRepository.findByEmailIgnoreCase(anyString()))
                .thenReturn(List.of(role("r1", "DDWMISSI", "DEV_TEAM", "Dev Team", false)));
        when(projectConfigRepository.findById("DDWMISSI"))
                .thenReturn(Optional.of(project("DDWMISSI", "Missao", "Segmento X", "Distribuicao")));

        UserProjectAccessDto access = service.resolveUserAccess(user("dev@empresa.com.br", null));

        assertFalse(access.isTransversalLeader());
        assertEquals(1, access.getProjects().size());
        verify(projectConfigRepository, never()).findAll();
    }

    @Test
    public void testPrimaryProjectPrefersUserDefaultWhenAccessible() {
        User user = user("joao@empresa.com.br", null);
        user.setDefaultProjectId("OUTRO");
        when(projectMemberRoleRepository.findByEmailIgnoreCase(anyString())).thenReturn(List.of(
                role("r1", "DDWMISSI", "DEV_TEAM", "Dev Team", false),
                role("r2", "OUTRO", "DEV_TEAM", "Dev Team", false)));
        when(projectConfigRepository.findById(anyString())).thenReturn(Optional.empty());

        assertEquals("OUTRO", service.resolveUserAccess(user).getPrimaryProjectId());
    }

    @Test
    public void testPrimaryProjectFallsBackWhenDefaultIsNotAccessible() {
        User user = user("joao@empresa.com.br", null);
        user.setDefaultProjectId("PROJETO-ALHEIO");
        when(projectMemberRoleRepository.findByEmailIgnoreCase(anyString()))
                .thenReturn(List.of(role("r1", "DDWMISSI", "DEV_TEAM", "Dev Team", false)));
        when(projectConfigRepository.findById(anyString())).thenReturn(Optional.empty());

        assertEquals("DDWMISSI", service.resolveUserAccess(user).getPrimaryProjectId());
    }
}
