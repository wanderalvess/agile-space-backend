package com.agilespace.backend.service;

import com.agilespace.backend.domain.JoltProject;
import com.agilespace.backend.domain.JoltProjectVersion;
import com.agilespace.backend.dto.JoltProjectDto;
import com.agilespace.backend.dto.SaveJoltProjectRequestDto;
import com.agilespace.backend.repository.JoltProjectRepository;
import com.agilespace.backend.repository.JoltProjectVersionRepository;
import com.agilespace.backend.service.JoltProjectService.Caller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JoltProjectServiceTest {

    @Mock
    private JoltProjectRepository projectRepository;

    @Mock
    private JoltProjectVersionRepository versionRepository;

    @InjectMocks
    private JoltProjectService projectService;

    private UUID projectId;
    private JoltProject project;
    private Caller author;
    private Caller outsider;
    private Caller squadMate;
    private Caller admin;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        project = JoltProject.builder()
                .id(projectId)
                .name("Layout PDVSync Pedidos")
                .description("Mapeamento de pedidos")
                .authorId("user-123")
                .authorName("Dev Teste")
                .isPublic(false)
                .squadId("SQUAD-A")
                .specJson("[{\"operation\": \"shift\"}]")
                .flowNodes("[]")
                .flowEdges("[]")
                .version(3L)
                .versions(new ArrayList<>())
                .build();
        author = new Caller("user-123", "MEMBER", "SQUAD-A", "Dev Teste", "dev@teste.com");
        outsider = new Caller("user-999", "MEMBER", "SQUAD-B", "Outro", null);
        squadMate = new Caller("user-456", "MEMBER", "SQUAD-A", "Colega", null);
        admin = new Caller("adm", "ADMIN", null, "Admin", null);
    }

    @Test
    @DisplayName("Deve criar projeto e gerar versão inicial v1 com o autor vindo do JWT")
    void testCreateProjectWithInitialVersion() {
        SaveJoltProjectRequestDto dto = SaveJoltProjectRequestDto.builder()
                .name("  Novo Layout ")
                .specJson("[]")
                .commitMessage("Primeiro commit")
                .build();

        when(projectRepository.saveAndFlush(any(JoltProject.class))).thenAnswer(inv -> {
            JoltProject p = inv.getArgument(0);
            p.setId(projectId);
            return p;
        });
        when(versionRepository.save(any(JoltProjectVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        JoltProjectDto result = projectService.createProject(dto, author);

        assertEquals("Novo Layout", result.getName());
        assertEquals("user-123", result.getAuthorId());
        assertEquals(1, result.getVersionCount());
        verify(versionRepository).save(argThat(v -> v.getVersionNumber() == 1 && "Primeiro commit".equals(v.getCommitMessage())));
    }

    @Test
    @DisplayName("Criar com squad de outra equipe é recusado; admin pode")
    void testCreateWithForeignSquad() {
        SaveJoltProjectRequestDto dto = SaveJoltProjectRequestDto.builder().name("X").squadId("SQUAD-B").build();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> projectService.createProject(dto, author));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Modo de mapeamento desconhecido é recusado")
    void testInvalidMappingMode() {
        SaveJoltProjectRequestDto dto = SaveJoltProjectRequestDto.builder().name("X").mappingMode("hack").build();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> projectService.createProject(dto, author));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("Deve atualizar projeto e gerar nova versão v2 quando houver commitMessage")
    void testUpdateProjectWithNewVersion() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(projectRepository.saveAndFlush(any(JoltProject.class))).thenReturn(project);

        JoltProjectVersion v1 = JoltProjectVersion.builder()
                .id(UUID.randomUUID())
                .project(project)
                .versionNumber(1)
                .build();
        when(versionRepository.findByProjectIdOrderByVersionNumberDesc(projectId)).thenReturn(List.of(v1));
        when(versionRepository.save(any(JoltProjectVersion.class))).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepository.countByProjectId(projectId)).thenReturn(2);

        SaveJoltProjectRequestDto dto = SaveJoltProjectRequestDto.builder()
                .name("Layout Atualizado")
                .commitMessage("Adiciona mapeamento de itens")
                .specJson("[{\"operation\": \"default\"}]")
                .build();

        JoltProjectDto result = projectService.updateProject(projectId, dto, author);

        assertEquals(2, result.getVersionCount());
        verify(versionRepository).save(argThat(v -> v.getVersionNumber() == 2 && "Adiciona mapeamento de itens".equals(v.getCommitMessage())));
    }

    @Test
    @DisplayName("Outro usuário (sem acesso) não vê, não altera e não exclui: 404")
    void testOutsiderGets404() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> projectService.getProject(projectId, outsider)).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> projectService.listVersions(projectId, outsider)).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> projectService.deleteProject(projectId, outsider)).getStatusCode());
        verify(projectRepository, never()).delete(any());
    }

    @Test
    @DisplayName("Colega da squad lê, mas não altera nem exclui: 403")
    void testSquadMateReadOnly() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(versionRepository.countByProjectId(projectId)).thenReturn(1);

        assertEquals("Layout PDVSync Pedidos", projectService.getProject(projectId, squadMate).getName());

        SaveJoltProjectRequestDto dto = SaveJoltProjectRequestDto.builder().name("Hack").build();
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> projectService.updateProject(projectId, dto, squadMate)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> projectService.deleteProject(projectId, squadMate)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> projectService.rollbackToVersion(projectId, UUID.randomUUID(), squadMate)).getStatusCode());
        verify(projectRepository, never()).saveAndFlush(any());
        assertEquals("Layout PDVSync Pedidos", project.getName());
    }

    @Test
    @DisplayName("Projeto público é legível por qualquer logado, mas só o autor escreve")
    void testPublicProjectReadable() {
        project.setIsPublic(true);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(versionRepository.countByProjectId(projectId)).thenReturn(1);

        assertNotNull(projectService.getProject(projectId, outsider));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> projectService.deleteProject(projectId, outsider)).getStatusCode());
    }

    @Test
    @DisplayName("Admin pode excluir projeto de outra pessoa")
    void testAdminDeletes() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        projectService.deleteProject(projectId, admin);
        verify(projectRepository).delete(project);
    }

    @Test
    @DisplayName("expectedVersion defasada recusa a gravação com 409 e não altera nada")
    void testStaleExpectedVersion() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        SaveJoltProjectRequestDto dto = SaveJoltProjectRequestDto.builder()
                .name("Minha edição").expectedVersion(2L).build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> projectService.updateProject(projectId, dto, author));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(projectRepository, never()).saveAndFlush(any());
        assertEquals("Layout PDVSync Pedidos", project.getName());
    }

    @Test
    @DisplayName("expectedVersion igual à atual deixa gravar; ausente mantém o comportamento antigo")
    void testMatchingOrMissingExpectedVersion() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(projectRepository.saveAndFlush(any(JoltProject.class))).thenReturn(project);
        when(versionRepository.countByProjectId(projectId)).thenReturn(1);

        projectService.updateProject(projectId,
                SaveJoltProjectRequestDto.builder().name("A").expectedVersion(3L).build(), author);
        projectService.updateProject(projectId,
                SaveJoltProjectRequestDto.builder().name("B").build(), author);

        assertEquals("B", project.getName());
    }

    @Test
    @DisplayName("Listagem usa as consultas restritas ao que o usuário pode ler e conta versões em lote")
    void testListUsesAccessibleQueries() {
        when(projectRepository.searchAccessibleProjects("user-999", "SQUAD-B", "pedido")).thenReturn(List.of(project));
        when(versionRepository.countByProjectIds(any())).thenReturn(java.util.Collections.singletonList(new Object[]{projectId, 4L}));

        List<JoltProjectDto> result = projectService.listProjects(outsider, " pedido ");

        assertEquals(1, result.size());
        assertEquals(4, result.get(0).getVersionCount());
        verify(projectRepository, never()).findAccessibleProjects(any(), any());
    }

    @Test
    @DisplayName("Deve realizar rollback para versão anterior")
    void testRollbackToVersion() {
        UUID v1Id = UUID.randomUUID();
        JoltProjectVersion v1 = JoltProjectVersion.builder()
                .id(v1Id)
                .project(project)
                .versionNumber(1)
                .specJson("[{\"operation\": \"shift-v1\"}]")
                .flowNodes("[{\"id\": \"node-1\"}]")
                .flowEdges("[]")
                .build();

        JoltProjectVersion v2 = JoltProjectVersion.builder()
                .id(UUID.randomUUID())
                .project(project)
                .versionNumber(2)
                .specJson("[{\"operation\": \"shift-v2\"}]")
                .build();

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(versionRepository.findById(v1Id)).thenReturn(Optional.of(v1));
        when(versionRepository.findByProjectIdOrderByVersionNumberDesc(projectId)).thenReturn(List.of(v2, v1));
        when(projectRepository.saveAndFlush(any(JoltProject.class))).thenReturn(project);
        when(versionRepository.save(any(JoltProjectVersion.class))).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepository.countByProjectId(projectId)).thenReturn(3);

        JoltProjectDto result = projectService.rollbackToVersion(projectId, v1Id, author);

        assertNotNull(result);
        assertEquals("[{\"operation\": \"shift-v1\"}]", project.getSpecJson());
        verify(versionRepository).save(argThat(v -> v.getVersionNumber() == 3 && v.getCommitMessage().contains("Rollback para a versão v1")));
    }

    @Test
    @DisplayName("Rollback com versão de outro projeto é recusado")
    void testRollbackForeignVersion() {
        JoltProject other = JoltProject.builder().id(UUID.randomUUID()).authorId("x").build();
        UUID vId = UUID.randomUUID();
        JoltProjectVersion foreign = JoltProjectVersion.builder().id(vId).project(other).versionNumber(1).build();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(versionRepository.findById(vId)).thenReturn(Optional.of(foreign));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> projectService.rollbackToVersion(projectId, vId, author));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }
}
