package com.agilespace.backend.controller;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.AuthResponseDto;
import com.agilespace.backend.dto.CreateProjectRequestDto;
import com.agilespace.backend.dto.ProjectDetailDto;
import com.agilespace.backend.dto.SegmentHierarchyDto;
import com.agilespace.backend.dto.UserProjectAccessDto;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.AuthService;
import com.agilespace.backend.service.JiraProfieldsService;
import com.agilespace.backend.service.UserProjectResolverService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

public class ProjectControllerTest {

    @Mock private JiraProfieldsService jiraProfieldsService;
    @Mock private UserProjectResolverService userProjectResolverService;
    @Mock private UserRepository userRepository;
    @Mock private AuthService authService;
    @Mock private HttpServletRequest httpServletRequest;

    @InjectMocks
    private ProjectController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testGetAllProjects() {
        when(jiraProfieldsService.getAllProjects()).thenReturn(List.of(ProjectDetailDto.builder().id("P1").build()));
        ResponseEntity<List<ProjectDetailDto>> response = controller.getAllProjects();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
    }

    @Test
    public void testGetHierarchy() {
        when(jiraProfieldsService.getSegmentHierarchy()).thenReturn(Collections.emptyList());
        ResponseEntity<List<SegmentHierarchyDto>> response = controller.getHierarchy();
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testGetProjectDetailsFound() {
        when(jiraProfieldsService.getProjectDetails("P1")).thenReturn(Optional.of(ProjectDetailDto.builder().id("P1").build()));
        ResponseEntity<ProjectDetailDto> response = controller.getProjectDetails("P1");
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testGetProjectDetailsNotFound() {
        when(jiraProfieldsService.getProjectDetails("P1")).thenReturn(Optional.empty());
        ResponseEntity<ProjectDetailDto> response = controller.getProjectDetails("P1");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    public void testCreateProjectMovesUserToNewProject() {
        User user = User.builder().id("u1").email("u1@empresa.com").build();
        when(httpServletRequest.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("u1");
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));

        CreateProjectRequestDto request = CreateProjectRequestDto.builder().id("meutime").name("Meu Time").build();
        AuthResponseDto authResponse = AuthResponseDto.builder().id("u1").activeProjectId("MEUTIME").build();
        when(authService.switchActiveProject("u1", "meutime")).thenReturn(authResponse);

        ResponseEntity<AuthResponseDto> response = controller.createProject(request, httpServletRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(jiraProfieldsService).createManualProject(request, user);
        assertEquals("MEUTIME", response.getBody().getActiveProjectId());
    }

    @Test
    public void testCreateProjectRejectsUnauthenticatedUser() {
        when(httpServletRequest.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("ghost");
        when(userRepository.findById("ghost")).thenReturn(Optional.empty());

        CreateProjectRequestDto request = CreateProjectRequestDto.builder().id("meutime").name("Meu Time").build();

        assertThrows(ResponseStatusException.class, () -> controller.createProject(request, httpServletRequest));
    }

    @Test
    public void testJoinProject() {
        User user = User.builder().id("u1").email("u1@empresa.com").build();
        when(httpServletRequest.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("u1");
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));
        when(authService.switchActiveProject("u1", "PROJ1")).thenReturn(AuthResponseDto.builder().id("u1").build());

        ResponseEntity<AuthResponseDto> response = controller.joinProject("PROJ1", "Developer", httpServletRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(jiraProfieldsService).joinProject("PROJ1", "Developer", user);
    }

    @Test
    public void testGetUserProjectsFindsById() {
        User user = User.builder().id("u1").email("u1@empresa.com").build();
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));
        when(userProjectResolverService.resolveUserAccess(user))
                .thenReturn(UserProjectAccessDto.builder().userId("u1").projects(Collections.emptyList()).build());

        ResponseEntity<UserProjectAccessDto> response = controller.getUserProjects("u1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("u1", response.getBody().getUserId());
    }

    @Test
    public void testGetUserProjectsFallsBackToTempUser() {
        when(userRepository.findById("desconhecido@empresa.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("desconhecido@empresa.com")).thenReturn(Optional.empty());
        when(userRepository.findByJiraAccountId("desconhecido@empresa.com")).thenReturn(Optional.empty());
        when(userProjectResolverService.resolveUserAccess(any(User.class)))
                .thenReturn(UserProjectAccessDto.builder().projects(Collections.emptyList()).build());

        ResponseEntity<UserProjectAccessDto> response = controller.getUserProjects("desconhecido@empresa.com");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(userProjectResolverService).resolveUserAccess(argThat(u -> "desconhecido@empresa.com".equals(u.getEmail())));
    }
}
