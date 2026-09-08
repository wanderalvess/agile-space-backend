package com.agilespace.backend.service;

import com.agilespace.backend.domain.ProjectConfig;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.CreateProjectRequestDto;
import com.agilespace.backend.dto.ProjectDetailDto;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("JiraProfieldsService - Integração de Projetos Profields/Jira e Papéis de Membros")
class JiraProfieldsServiceTest {

    @Mock private ProjectConfigRepository projectConfigRepository;
    @Mock private ProjectMemberRoleRepository projectMemberRoleRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks
    private JiraProfieldsService service;

    @Nested
    @DisplayName("Validação de Token e Sincronização Profields")
    class ValidationTests {

        @Test
        @DisplayName("Deve exigir token PAT do Jira ao tentar sincronizar projeto Profields")
        void syncProjectFromProfieldsRequiresToken() {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.syncProjectFromProfields("empresa.atlassian.net", "PROJ1", null));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());

            ResponseStatusException exBlank = assertThrows(ResponseStatusException.class,
                    () -> service.syncProjectFromProfields("empresa.atlassian.net", "PROJ1", "   "));
            assertEquals(HttpStatus.BAD_REQUEST, exBlank.getStatusCode());

            verifyNoInteractions(projectConfigRepository);
        }

        @Test
        @DisplayName("Deve retornar vazio quando projeto Profields não for encontrado")
        void getProjectDetailsReturnsEmptyWhenNotFound() {
            when(projectConfigRepository.findById("PROJ1")).thenReturn(Optional.empty());

            Optional<ProjectDetailDto> result = service.getProjectDetails("proj1");

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("Criação Manual e Ingressão de Membros")
    class ManualProjectAndMembershipTests {

        @Test
        @DisplayName("Deve rejeitar criação de projeto com ID duplicado (HTTP 409 Conflict)")
        void createManualProjectRejectsDuplicateKey() {
            when(projectConfigRepository.existsById("MEUTIME")).thenReturn(true);
            CreateProjectRequestDto request = CreateProjectRequestDto.builder().id("meutime").name("Meu Time").build();
            User creator = User.builder().id("u1").name("Criador").email("criador@empresa.com").build();

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.createManualProject(request, creator));
            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        }

        @Test
        @DisplayName("Deve atribuir papel Agile Master com liderança ao criador do projeto")
        void createManualProjectMakesCreatorAgileMaster() {
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
        @DisplayName("Deve rejeitar auto-atribuição de papel de liderança (Product Owner/Scrum Master) ao entrar")
        void joinProjectRejectsLeadershipRole() {
            when(projectConfigRepository.existsById("PROJ1")).thenReturn(true);
            when(projectMemberRoleRepository.findByProjectId("PROJ1")).thenReturn(Collections.emptyList());

            User user = User.builder().id("u1").name("User").email("user@empresa.com").jiraAccountId("user.acc").build();

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.joinProject("proj1", "Product Owner", user));
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
            verify(projectMemberRoleRepository, never()).save(any(ProjectMemberRole.class));
        }

        @Test
        @DisplayName("Deve aceitar papel de desenvolvedor sem flag de liderança")
        void joinProjectAcceptsContributorRoleWithoutLeadership() {
            when(projectConfigRepository.existsById("PROJ1")).thenReturn(true);
            when(projectMemberRoleRepository.findByProjectId("PROJ1")).thenReturn(Collections.emptyList());
            when(projectMemberRoleRepository.save(any(ProjectMemberRole.class))).thenAnswer(i -> i.getArgument(0));

            User user = User.builder().id("u1").name("User").email("user@empresa.com").jiraAccountId("user.acc").build();
            service.joinProject("proj1", "Developer", user);

            ArgumentCaptor<ProjectMemberRole> captor = ArgumentCaptor.forClass(ProjectMemberRole.class);
            verify(projectMemberRoleRepository).save(captor.capture());
            assertFalse(captor.getValue().isLeadership());
            assertEquals("DEVELOPER", captor.getValue().getRoleKey());
        }
    }
}
