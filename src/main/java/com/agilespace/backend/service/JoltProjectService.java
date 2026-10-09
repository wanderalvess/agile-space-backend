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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Projetos de transformação JOLT salvos. Toda autorização usa a identidade do JWT (nunca o corpo):
 * <ul>
 *   <li>Ler: autor, qualquer logado se o projeto for público, mesma squad, ou ADMIN.</li>
 *   <li>Alterar / excluir / restaurar versão: só o autor ou ADMIN.</li>
 * </ul>
 * Quem não pode ler recebe 404 (não revela a existência do projeto).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JoltProjectService {

    private static final Set<String> MAPPING_MODES = Set.of("smarthub", "direct");

    private final JoltProjectRepository projectRepository;
    private final JoltProjectVersionRepository versionRepository;

    /** Quem está chamando, vindo do JWT (id e papel) e do cadastro (squad). */
    public record Caller(String userId, String role, String squadId, String name, String email) {
        public boolean isAdmin() {
            return "ADMIN".equalsIgnoreCase(role);
        }
    }

    @Transactional(readOnly = true)
    public List<JoltProjectDto> listProjects(Caller caller, String search) {
        List<JoltProject> projects;
        if (search != null && !search.trim().isEmpty()) {
            projects = projectRepository.searchAccessibleProjects(caller.userId(), caller.squadId(), search.trim());
        } else {
            projects = projectRepository.findAccessibleProjects(caller.userId(), caller.squadId());
        }
        Map<UUID, Integer> counts = countVersions(projects);
        return projects.stream()
                .map(p -> toDto(p, counts.getOrDefault(p.getId(), 0)))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public JoltProjectDto getProject(UUID id, Caller caller) {
        JoltProject project = loadReadable(id, caller);
        return toDto(project, versionRepository.countByProjectId(project.getId()));
    }

    @Transactional
    public JoltProjectDto createProject(SaveJoltProjectRequestDto dto, Caller caller) {
        String squadId = validatedSquad(dto.getSquadId(), caller);
        String name = requireName(dto.getName());

        JoltProject project = JoltProject.builder()
                .name(name)
                .description(dto.getDescription())
                .category(dto.getCategory() != null ? dto.getCategory() : "Geral")
                .entityName(dto.getEntityName())
                .mappingMode(validatedMode(dto.getMappingMode(), "smarthub"))
                .isPublic(Boolean.TRUE.equals(dto.getIsPublic()))
                .authorId(caller.userId())
                .authorName(caller.name() != null ? caller.name() : "Usuário")
                .authorEmail(caller.email())
                .squadId(squadId)
                .inputJson(dto.getInputJson())
                .targetJson(dto.getTargetJson())
                .specJson(dto.getSpecJson())
                .flowNodes(dto.getFlowNodes())
                .flowEdges(dto.getFlowEdges())
                .build();

        project = projectRepository.saveAndFlush(project);

        // Cria versão inicial 1
        String commitMsg = (dto.getCommitMessage() != null && !dto.getCommitMessage().isBlank())
                ? dto.getCommitMessage().trim()
                : "Versão inicial (v1)";

        versionRepository.save(snapshot(project, 1, commitMsg, caller));

        log.info("Projeto JOLT '{}' criado com sucesso pelo usuário {}", project.getName(), caller.userId());
        return toDto(project, 1);
    }

    @Transactional
    public JoltProjectDto updateProject(UUID id, SaveJoltProjectRequestDto dto, Caller caller) {
        JoltProject project = loadWritable(id, caller);

        // Edição concorrente (opt-in): o cliente manda a versão que carregou; se mudou, recusa em vez de sobrescrever.
        if (dto.getExpectedVersion() != null && !dto.getExpectedVersion().equals(project.getVersion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Este projeto foi alterado em outra aba ou por outra pessoa. Recarregue antes de salvar para não perder alterações.");
        }

        project.setName(requireName(dto.getName()));
        project.setDescription(dto.getDescription());
        if (dto.getCategory() != null) project.setCategory(dto.getCategory());
        if (dto.getEntityName() != null) project.setEntityName(dto.getEntityName());
        if (dto.getMappingMode() != null) project.setMappingMode(validatedMode(dto.getMappingMode(), "smarthub"));
        if (dto.getIsPublic() != null) project.setIsPublic(dto.getIsPublic());
        if (dto.getSquadId() != null) project.setSquadId(validatedSquad(dto.getSquadId(), caller));

        project.setInputJson(dto.getInputJson());
        project.setTargetJson(dto.getTargetJson());
        project.setSpecJson(dto.getSpecJson());
        project.setFlowNodes(dto.getFlowNodes());
        project.setFlowEdges(dto.getFlowEdges());

        project = projectRepository.saveAndFlush(project);

        // Se uma mensagem de commit foi fornecida, cria nova versão
        if (dto.getCommitMessage() != null && !dto.getCommitMessage().trim().isEmpty()) {
            int nextVersionNumber = nextVersionNumber(project.getId());
            versionRepository.save(snapshot(project, nextVersionNumber, dto.getCommitMessage().trim(), caller));
            log.info("Nova versão v{} criada para o projeto JOLT '{}'", nextVersionNumber, project.getName());
        }

        return toDto(project, versionRepository.countByProjectId(project.getId()));
    }

    @Transactional
    public void deleteProject(UUID id, Caller caller) {
        JoltProject project = loadWritable(id, caller);
        projectRepository.delete(project);
        log.info("Projeto JOLT '{}' (id: {}) excluído pelo usuário {}", project.getName(), id, caller.userId());
    }

    @Transactional(readOnly = true)
    public List<JoltProjectVersionDto> listVersions(UUID projectId, Caller caller) {
        loadReadable(projectId, caller);
        return versionRepository.findByProjectIdOrderByVersionNumberDesc(projectId)
                .stream()
                .map(this::toVersionDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public JoltProjectDto rollbackToVersion(UUID projectId, UUID versionId, Caller caller) {
        JoltProject project = loadWritable(projectId, caller);

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

        project = projectRepository.saveAndFlush(project);

        // Cria versão de rollback
        int nextVersionNumber = nextVersionNumber(project.getId());
        versionRepository.save(snapshot(project, nextVersionNumber,
                "Rollback para a versão v" + targetVersion.getVersionNumber(), caller));

        log.info("Rollback do projeto '{}' para versão v{} executado com sucesso (nova v{})",
                project.getName(), targetVersion.getVersionNumber(), nextVersionNumber);

        return toDto(project, versionRepository.countByProjectId(project.getId()));
    }

    // ── Autorização ─────────────────────────────────────────────────────────

    private JoltProject loadReadable(UUID id, Caller caller) {
        JoltProject project = projectRepository.findById(id).orElseThrow(JoltProjectService::notFound);
        if (!canRead(project, caller)) throw notFound();
        return project;
    }

    private JoltProject loadWritable(UUID id, Caller caller) {
        JoltProject project = loadReadable(id, caller);
        if (!canWrite(project, caller)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Só quem criou o projeto pode alterá-lo ou excluí-lo.");
        }
        return project;
    }

    static boolean canRead(JoltProject p, Caller c) {
        if (c.isAdmin()) return true;
        if (c.userId() != null && c.userId().equals(p.getAuthorId())) return true;
        if (Boolean.TRUE.equals(p.getIsPublic())) return true;
        return p.getSquadId() != null && !p.getSquadId().isBlank() && p.getSquadId().equals(c.squadId());
    }

    static boolean canWrite(JoltProject p, Caller c) {
        return c.isAdmin() || (c.userId() != null && c.userId().equals(p.getAuthorId()));
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Projeto JOLT não encontrado");
    }

    /** Só dá para compartilhar com a própria squad (admin escolhe qualquer uma). Vazio vira sem squad. */
    private static String validatedSquad(String requested, Caller caller) {
        if (requested == null || requested.isBlank()) return null;
        if (caller.isAdmin() || requested.equals(caller.squadId())) return requested;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Só é possível compartilhar o projeto com a sua própria squad.");
    }

    private static String validatedMode(String mode, String fallback) {
        if (mode == null || mode.isBlank()) return fallback;
        if (!MAPPING_MODES.contains(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Modo de mapeamento inválido.");
        }
        return mode;
    }

    private static String requireName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O nome do projeto é obrigatório");
        }
        return name.trim();
    }

    // ── Versões ─────────────────────────────────────────────────────────────

    private int nextVersionNumber(UUID projectId) {
        List<JoltProjectVersion> existing = versionRepository.findByProjectIdOrderByVersionNumberDesc(projectId);
        return existing.isEmpty() ? 1 : existing.get(0).getVersionNumber() + 1;
    }

    private JoltProjectVersion snapshot(JoltProject project, int number, String message, Caller caller) {
        return JoltProjectVersion.builder()
                .project(project)
                .versionNumber(number)
                .commitMessage(message)
                .specJson(project.getSpecJson())
                .flowNodes(project.getFlowNodes())
                .flowEdges(project.getFlowEdges())
                .inputJson(project.getInputJson())
                .targetJson(project.getTargetJson())
                .createdBy(caller.userId())
                .authorName(caller.name())
                .build();
    }

    /** Contagem em uma consulta só; evita carregar todas as versões (texto grande) de cada projeto na listagem. */
    private Map<UUID, Integer> countVersions(List<JoltProject> projects) {
        Map<UUID, Integer> counts = new HashMap<>();
        if (projects.isEmpty()) return counts;
        List<UUID> ids = projects.stream().map(JoltProject::getId).toList();
        for (Object[] row : versionRepository.countByProjectIds(ids)) {
            counts.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }

    private JoltProjectDto toDto(JoltProject p, int versionCount) {
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
                .versionCount(versionCount)
                .version(p.getVersion())
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
