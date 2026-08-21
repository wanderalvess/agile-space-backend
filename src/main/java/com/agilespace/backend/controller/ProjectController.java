package com.agilespace.backend.controller;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.ProjectDetailDto;
import com.agilespace.backend.dto.SegmentHierarchyDto;
import com.agilespace.backend.dto.UserProjectAccessDto;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.service.JiraProfieldsService;
import com.agilespace.backend.service.UserProjectResolverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class ProjectController {

    private final JiraProfieldsService jiraProfieldsService;
    private final UserProjectResolverService userProjectResolverService;
    private final UserRepository userRepository;

    /**
     * Lista todos os projetos cadastrados com seus metadados de governança.
     */
    @GetMapping
    public ResponseEntity<List<ProjectDetailDto>> getAllProjects() {
        return ResponseEntity.ok(jiraProfieldsService.getAllProjects());
    }

    /**
     * Retorna a visão hierárquica por Segmento e Tribo.
     */
    @GetMapping("/hierarchy")
    public ResponseEntity<List<SegmentHierarchyDto>> getHierarchy() {
        return ResponseEntity.ok(jiraProfieldsService.getSegmentHierarchy());
    }

    /**
     * Retorna os detalhes de um projeto específico.
     */
    @GetMapping("/{projectKey}")
    public ResponseEntity<ProjectDetailDto> getProjectDetails(@PathVariable String projectKey) {
        return jiraProfieldsService.getProjectDetails(projectKey)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Sincroniza um projeto diretamente com a API Profields do Jira TOTVS.
     */
    @PostMapping("/sync/{projectKey}")
    public ResponseEntity<ProjectDetailDto> syncProject(
            @PathVariable String projectKey,
            @RequestParam(required = false) String domain,
            @RequestHeader(value = "X-Jira-Token", required = false) String token) {
        ProjectDetailDto synced = jiraProfieldsService.syncProjectFromProfields(domain, projectKey, token);
        return ResponseEntity.ok(synced);
    }

    /**
     * Resolve os projetos e cargos acessíveis para um usuário.
     */
    @GetMapping("/user/{identifier}")
    public ResponseEntity<UserProjectAccessDto> getUserProjects(@PathVariable String identifier) {
        Optional<User> userOpt = userRepository.findById(identifier);
        if (userOpt.isEmpty()) {
            userOpt = userRepository.findByEmail(identifier);
        }
        if (userOpt.isEmpty()) {
            userOpt = userRepository.findByJiraAccountId(identifier);
        }

        if (userOpt.isEmpty()) {
            // Cria um objeto temporário para resolver papéis por email direto
            User tempUser = User.builder()
                    .id("temp-" + identifier)
                    .email(identifier.contains("@") ? identifier : identifier + "@empresa.com.br")
                    .jiraAccountId(identifier)
                    .name(identifier)
                    .build();
            return ResponseEntity.ok(userProjectResolverService.resolveUserAccess(tempUser));
        }

        return ResponseEntity.ok(userProjectResolverService.resolveUserAccess(userOpt.get()));
    }
}
