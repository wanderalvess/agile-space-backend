package com.agilespace.backend;

import com.agilespace.backend.domain.ProjectConfig;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.CreateProjectRequestDto;
import com.agilespace.backend.dto.ProjectDetailDto;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.service.JiraProfieldsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class JiraProfieldsServiceTest {

    @Mock private ProjectConfigRepository projectConfigRepository;
    @Mock private ProjectMemberRoleRepository projectMemberRoleRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks
    private JiraProfieldsService service;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void syncProjectFromProfieldsRequiresToken() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.syncProjectFromProfields("empresa.atlassian.net", "PROJ1", null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());

        ResponseStatusException exBlank = assertThrows(ResponseStatusException.class,
                () -> service.syncProjectFromProfields("empresa.atlassian.net", "PROJ1", "   "));
        assertEquals(HttpStatus.BAD_REQUEST, exBlank.getStatusCode());

        verifyNoInteractions(projectConfigRepository);
    }

    @Test
    public void getProjectDetailsReturnsEmptyWhenNotFound() {
        when(projectConfigRepository.findById("PROJ1")).thenReturn(Optional.empty());
        Optional<ProjectDetailDto> result = service.getProjectDetails("proj1");
        assertTrue(result.isEmpty());
    }

    @Test
    public void getProjectDetailsMapsMembers() {
        ProjectConfig config = ProjectConfig.builder().id("PROJ1").name("Projeto Um").build();
        ProjectMemberRole member = ProjectMemberRole.builder()
                .projectId("PROJ1").roleName("Product Owner").roleKey("PRODUCT_OWNER")
                .displayName("Fulano").isLeadership(true).build();

        when(projectConfigRepository.findById("PROJ1")).thenReturn(Optional.of(config));
        when(projectMemberRoleRepository.findByProjectId("PROJ1")).thenReturn(Arrays.asList(member));

        Optional<ProjectDetailDto> result = service.getProjectDetails("PROJ1");
        assertTrue(result.isPresent());
        assertEquals(1, result.get().getMembers().size());
        assertEquals("Fulano", result.get().getMembers().get(0).getDisplayName());
    }

    @Test
    public void createManualProjectRejectsDuplicateKey() {
        when(projectConfigRepository.existsById("MEUTIME")).thenReturn(true);
        CreateProjectRequestDto request = CreateProjectRequestDto.builder().id("meutime").name("Meu Time").build();
        User creator = User.builder().id("u1").name("Criador").email("criador@empresa.com").build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createManualProject(request, creator));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    public void createManualProjectMakesCreatorAgileMaster() {
        when(projectConfigRepository.existsById("MEUTIME")).thenReturn(false);
        when(projectConfigRepository.save(any(ProjectConfig.class))).thenAnswer(i -> i.getArgument(0));
        when(projectMemberRoleRepository.save(any(ProjectMemberRole.class))).thenAnswer(i -> i.getArgument(0));

        CreateProjectRequestDto request = CreateProjectRequestDto.builder().id("meutime").name("Meu Time").build();
        User creator = User.builder().id("u1").name("Criador").email("criador@empresa.com").build();

        ProjectDetailDto result = service.createManualProject(request, creator);

        assertEquals("MEUTIME", result.getId());
        assertEquals(1, result.getMembers().size());
        assertEquals("AGILE_MASTER", result.getMembers().get(0).getRoleKey());
        assertTrue(result.getMembers().get(0).isLeadership());
    }

    @Test
    public void joinProjectThrowsWhenProjectMissing() {
        when(projectConfigRepository.existsById("PROJ1")).thenReturn(false);
        User user = User.builder().id("u1").name("User").email("user@empresa.com").build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.joinProject("proj1", "Developer", user));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    public void joinProjectSkipsWhenAlreadyMember() {
        when(projectConfigRepository.existsById("PROJ1")).thenReturn(true);
        ProjectMemberRole existing = ProjectMemberRole.builder().projectId("PROJ1").userId("u1").build();
        when(projectMemberRoleRepository.findByProjectId("PROJ1")).thenReturn(Arrays.asList(existing));

        User user = User.builder().id("u1").name("User").email("user@empresa.com").build();
        service.joinProject("proj1", "Developer", user);

        verify(projectMemberRoleRepository, never()).save(any(ProjectMemberRole.class));
    }

    @Test
    public void joinProjectDetectsLeadershipRole() {
        when(projectConfigRepository.existsById("PROJ1")).thenReturn(true);
        when(projectMemberRoleRepository.findByProjectId("PROJ1")).thenReturn(Collections.emptyList());
        when(projectMemberRoleRepository.save(any(ProjectMemberRole.class))).thenAnswer(i -> i.getArgument(0));

        User user = User.builder().id("u1").name("User").email("user@empresa.com").jiraAccountId("user.acc").build();
        service.joinProject("proj1", "Product Owner", user);

        ArgumentCaptor<ProjectMemberRole> captor = ArgumentCaptor.forClass(ProjectMemberRole.class);
        verify(projectMemberRoleRepository).save(captor.capture());
        assertTrue(captor.getValue().isLeadership());
        assertEquals("PRODUCT_OWNER", captor.getValue().getRoleKey());
    }

    @Test
    public void getAllProjectsMapsRepositoryResults() {
        ProjectConfig config = ProjectConfig.builder().id("PROJ1").name("Projeto Um").build();
        when(projectConfigRepository.findAllByOrderBySegmentNameAscNameAsc()).thenReturn(Arrays.asList(config));
        when(projectMemberRoleRepository.findByProjectId("PROJ1")).thenReturn(Collections.emptyList());

        List<ProjectDetailDto> result = service.getAllProjects();
        assertEquals(1, result.size());
        assertEquals("PROJ1", result.get(0).getId());
    }
}
