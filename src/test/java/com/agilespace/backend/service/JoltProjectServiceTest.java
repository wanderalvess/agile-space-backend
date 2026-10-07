package com.agilespace.backend.service;

import com.agilespace.backend.domain.JoltProject;
import com.agilespace.backend.domain.JoltProjectVersion;
import com.agilespace.backend.dto.JoltProjectDto;
import com.agilespace.backend.dto.SaveJoltProjectRequestDto;
import com.agilespace.backend.repository.JoltProjectRepository;
import com.agilespace.backend.repository.JoltProjectVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        project = JoltProject.builder()
                .id(projectId)
                .name("Layout PDVSync Pedidos")
                .description("Mapeamento de pedidos")
                .authorId("user-123")
                .authorName("Dev Teste")
                .specJson("[{\"operation\": \"shift\"}]")
                .flowNodes("[]")
                .flowEdges("[]")
                .versions(new ArrayList<>())
                .build();
    }

    @Test
    @DisplayName("Deve criar projeto e gerar versão inicial v1")
    void testCreateProjectWithInitialVersion() {
        SaveJoltProjectRequestDto dto = SaveJoltProjectRequestDto.builder()
                .name("Novo Layout")
                .specJson("[]")
                .commitMessage("Primeiro commit")
                .build();

        when(projectRepository.save(any(JoltProject.class))).thenAnswer(inv -> {
            JoltProject p = inv.getArgument(0);
            p.setId(projectId);
            return p;
        });

        when(versionRepository.save(any(JoltProjectVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        JoltProjectDto result = projectService.createProject(dto, "user-123", "Dev Teste", "dev@teste.com");

        assertNotNull(result);
        assertEquals("Novo Layout", result.getName());
        verify(projectRepository).save(any(JoltProject.class));
        verify(versionRepository).save(any(JoltProjectVersion.class));
    }

    @Test
    @DisplayName("Deve atualizar projeto e gerar nova versão v2 quando houver commitMessage")
    void testUpdateProjectWithNewVersion() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(projectRepository.save(any(JoltProject.class))).thenReturn(project);

        JoltProjectVersion v1 = JoltProjectVersion.builder()
                .id(UUID.randomUUID())
                .project(project)
                .versionNumber(1)
                .build();
        when(versionRepository.findByProjectIdOrderByVersionNumberDesc(projectId)).thenReturn(List.of(v1));
        when(versionRepository.save(any(JoltProjectVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        SaveJoltProjectRequestDto dto = SaveJoltProjectRequestDto.builder()
                .name("Layout Atualizado")
                .commitMessage("Adiciona mapeamento de itens")
                .specJson("[{\"operation\": \"default\"}]")
                .build();

        JoltProjectDto result = projectService.updateProject(projectId, dto, "user-123", "Dev Teste");

        assertNotNull(result);
        verify(versionRepository).save(argThat(v -> v.getVersionNumber() == 2 && "Adiciona mapeamento de itens".equals(v.getCommitMessage())));
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
        when(projectRepository.save(any(JoltProject.class))).thenReturn(project);
        when(versionRepository.save(any(JoltProjectVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        JoltProjectDto result = projectService.rollbackToVersion(projectId, v1Id, "user-123", "Dev Teste");

        assertNotNull(result);
        assertEquals("[{\"operation\": \"shift-v1\"}]", project.getSpecJson());
        verify(versionRepository).save(argThat(v -> v.getVersionNumber() == 3 && v.getCommitMessage().contains("Rollback para a versão v1")));
    }
}
