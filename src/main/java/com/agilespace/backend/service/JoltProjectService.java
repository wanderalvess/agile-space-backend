package com.agilespace.backend.service;

import com.agilespace.backend.domain.JoltProject;
import com.agilespace.backend.domain.JoltProjectVersion;
import com.agilespace.backend.dto.JoltProjectDto;
import com.agilespace.backend.dto.JoltProjectVersionDto;
import com.agilespace.backend.dto.SaveJoltProjectRequestDto;
import com.agilespace.backend.repository.JoltProjectRepository;
import com.agilespace.backend.repository.JoltProjectVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class JoltProjectService {

    private final JoltProjectRepository projectRepository;
    private final JoltProjectVersionRepository versionRepository;

    @Transactional(readOnly = true)
    public List<JoltProjectDto> listProjects(String userId, String squadId, String search) {
        List<JoltProject> projects;
        if (search != null && !search.trim().isEmpty()) {
            projects = projectRepository.searchProjects(search.trim());
        } else {
            projects = projectRepository.findAccessibleProjects(userId, squadId);
        }
        return projects.stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public JoltProjectDto getProject(UUID id) {
        JoltProject project = projectRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Projeto JOLT não encontrado"));
        return toDto(project);
    }

    @Transactional
    public JoltProjectDto createProject(SaveJoltProjectRequestDto dto, String userId, String userName, String userEmail) {
        JoltProject project = JoltProject.builder()
                .name(dto.getName().trim())
                .description(dto.getDescription())
                .category(dto.getCategory() != null ? dto.getCategory() : "Geral")
                .entityName(dto.getEntityName())
                .mappingMode(dto.getMappingMode() != null ? dto.getMappingMode() : "smarthub")
                .isPublic(Boolean.TRUE.equals(dto.getIsPublic()))
                .authorId(userId)
                .authorName(userName != null ? userName : "Usuário")
                .authorEmail(userEmail)
                .squadId(dto.getSquadId())
                .inputJson(dto.getInputJson())
                .targetJson(dto.getTargetJson())
                .specJson(dto.getSpecJson())
                .flowNodes(dto.getFlowNodes())
                .flowEdges(dto.getFlowEdges())
                .build();

        project = projectRepository.save(project);

        // Cria versão inicial 1
        String commitMsg = (dto.getCommitMessage() != null && !dto.getCommitMessage().isBlank())
                ? dto.getCommitMessage()
                : "Versão inicial (v1)";

        JoltProjectVersion initialVersion = JoltProjectVersion.builder()
                .project(project)
                .versionNumber(1)
                .commitMessage(commitMsg)
                .specJson(project.getSpecJson())
                .flowNodes(project.getFlowNodes())
                .flowEdges(project.getFlowEdges())
                .inputJson(project.getInputJson())
                .targetJson(project.getTargetJson())
                .createdBy(userId)
                .authorName(userName)
                .build();

        versionRepository.save(initialVersion);
        project.getVersions().add(initialVersion);

        log.info("Projeto JOLT '{}' criado com sucesso pelo usuário {}", project.getName(), userId);
        return toDto(project);
    }

    @Transactional
    public JoltProjectDto updateProject(UUID id, SaveJoltProjectRequestDto dto, String userId, String userName) {
        JoltProject project = projectRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Projeto JOLT não encontrado"));

        project.setName(dto.getName().trim());
        project.setDescription(dto.getDescription());
        if (dto.getCategory() != null) project.setCategory(dto.getCategory());
        if (dto.getEntityName() != null) project.setEntityName(dto.getEntityName());
        if (dto.getMappingMode() != null) project.setMappingMode(dto.getMappingMode());
        if (dto.getIsPublic() != null) project.setIsPublic(dto.getIsPublic());
        if (dto.getSquadId() != null) project.setSquadId(dto.getSquadId());

        project.setInputJson(dto.getInputJson());
        project.setTargetJson(dto.getTargetJson());
        project.setSpecJson(dto.getSpecJson());
        project.setFlowNodes(dto.getFlowNodes());
        project.setFlowEdges(dto.getFlowEdges());

        project = projectRepository.save(project);

        // Se uma mensagem de commit foi fornecida, cria nova versão
        if (dto.getCommitMessage() != null && !dto.getCommitMessage().trim().isEmpty()) {
            List<JoltProjectVersion> existingVersions = versionRepository.findByProjectIdOrderByVersionNumberDesc(project.getId());
            int nextVersionNumber = existingVersions.isEmpty() ? 1 : (existingVersions.get(0).getVersionNumber() + 1);

            JoltProjectVersion version = JoltProjectVersion.builder()
                    .project(project)
                    .versionNumber(nextVersionNumber)
                    .commitMessage(dto.getCommitMessage().trim())
                    .specJson(project.getSpecJson())
                    .flowNodes(project.getFlowNodes())
                    .flowEdges(project.getFlowEdges())
                    .inputJson(project.getInputJson())
                    .targetJson(project.getTargetJson())
                    .createdBy(userId)
                    .authorName(userName)
                    .build();

            versionRepository.save(version);
            project.getVersions().add(0, version);
            log.info("Nova versão v{} criada para o projeto JOLT '{}'", nextVersionNumber, project.getName());
        }

        return toDto(project);
    }

    @Transactional
    public void deleteProject(UUID id, String userId) {
        JoltProject project = projectRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Projeto JOLT não encontrado"));

        projectRepository.delete(project);
        log.info("Projeto JOLT '{}' (id: {}) excluído pelo usuário {}", project.getName(), id, userId);
    }

    @Transactional(readOnly = true)
    public List<JoltProjectVersionDto> listVersions(UUID projectId) {
        return versionRepository.findByProjectIdOrderByVersionNumberDesc(projectId)
                .stream()
                .map(this::toVersionDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public JoltProjectDto rollbackToVersion(UUID projectId, UUID versionId, String userId, String userName) {
        JoltProject project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Projeto JOLT não encontrado"));

        JoltProjectVersion targetVersion = versionRepository.findById(versionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Versão não encontrada"));

        if (!targetVersion.getProject().getId().equals(projectId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A versão selecionada não pertence a este projeto");
        }

        // Restaura os dados da versão
        project.setSpecJson(targetVersion.getSpecJson());
        project.setFlowNodes(targetVersion.getFlowNodes());
        project.setFlowEdges(targetVersion.getFlowEdges());
        if (targetVersion.getInputJson() != null) project.setInputJson(targetVersion.getInputJson());
        if (targetVersion.getTargetJson() != null) project.setTargetJson(targetVersion.getTargetJson());

        project = projectRepository.save(project);

        // Cria versão de rollback
        List<JoltProjectVersion> existingVersions = versionRepository.findByProjectIdOrderByVersionNumberDesc(project.getId());
        int nextVersionNumber = existingVersions.isEmpty() ? 1 : (existingVersions.get(0).getVersionNumber() + 1);

        JoltProjectVersion rollbackVersion = JoltProjectVersion.builder()
                .project(project)
                .versionNumber(nextVersionNumber)
                .commitMessage("Rollback para a versão v" + targetVersion.getVersionNumber())
                .specJson(project.getSpecJson())
                .flowNodes(project.getFlowNodes())
                .flowEdges(project.getFlowEdges())
                .inputJson(project.getInputJson())
                .targetJson(project.getTargetJson())
                .createdBy(userId)
                .authorName(userName)
                .build();

        versionRepository.save(rollbackVersion);
        project.getVersions().add(0, rollbackVersion);

        log.info("Rollback do projeto '{}' para versão v{} executado com sucesso (nova v{})",
                project.getName(), targetVersion.getVersionNumber(), nextVersionNumber);

        return toDto(project);
    }

    private JoltProjectDto toDto(JoltProject p) {
        return JoltProjectDto.builder()
                .id(p.getId())
                .name(p.getName())
                .description(p.getDescription())
                .category(p.getCategory())
                .entityName(p.getEntityName())
                .mappingMode(p.getMappingMode())
                .isPublic(p.getIsPublic())
                .authorId(p.getAuthorId())
                .authorName(p.getAuthorName())
                .authorEmail(p.getAuthorEmail())
                .squadId(p.getSquadId())
                .inputJson(p.getInputJson())
                .targetJson(p.getTargetJson())
                .specJson(p.getSpecJson())
                .flowNodes(p.getFlowNodes())
                .flowEdges(p.getFlowEdges())
                .versionCount(p.getVersions() != null ? p.getVersions().size() : 0)
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    private JoltProjectVersionDto toVersionDto(JoltProjectVersion v) {
        return JoltProjectVersionDto.builder()
                .id(v.getId())
                .projectId(v.getProject().getId())
                .versionNumber(v.getVersionNumber())
                .commitMessage(v.getCommitMessage())
                .specJson(v.getSpecJson())
                .flowNodes(v.getFlowNodes())
                .flowEdges(v.getFlowEdges())
                .inputJson(v.getInputJson())
                .targetJson(v.getTargetJson())
                .createdBy(v.getCreatedBy())
                .authorName(v.getAuthorName())
                .createdAt(v.getCreatedAt())
                .build();
    }
}
