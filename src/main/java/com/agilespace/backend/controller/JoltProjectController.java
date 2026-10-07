package com.agilespace.backend.controller;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.JoltProjectDto;
import com.agilespace.backend.dto.JoltProjectVersionDto;
import com.agilespace.backend.dto.SaveJoltProjectRequestDto;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.JoltProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/jolt/projects")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
@Tag(name = "JOLT Projects", description = "Gestão corporativa e persistência de projetos e versões JOLT")
public class JoltProjectController {

    private final JoltProjectService joltProjectService;
    private final UserRepository userRepository;

    private User resolveUser(HttpServletRequest request) {
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        if (userId == null) return null;
        return userRepository.findById(userId).orElse(null);
    }

    @Operation(summary = "Lista projetos JOLT acessíveis ao usuário")
    @GetMapping
    public ResponseEntity<List<JoltProjectDto>> listProjects(
            @RequestParam(value = "search", required = false) String search,
            HttpServletRequest request
    ) {
        User user = resolveUser(request);
        String userId = user != null ? user.getId() : "anonymous";
        String squadId = user != null ? user.getSquadId() : null;

        List<JoltProjectDto> list = joltProjectService.listProjects(userId, squadId, search);
        return ResponseEntity.ok(list);
    }

    @Operation(summary = "Obtém detalhes completos de um projeto JOLT")
    @GetMapping("/{id}")
    public ResponseEntity<JoltProjectDto> getProject(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(joltProjectService.getProject(id));
    }

    @Operation(summary = "Cria um novo projeto JOLT com versão inicial v1")
    @PostMapping
    public ResponseEntity<JoltProjectDto> createProject(
            @Valid @RequestBody SaveJoltProjectRequestDto dto,
            HttpServletRequest request
    ) {
        User user = resolveUser(request);
        String userId = user != null ? user.getId() : "local-user";
        String userName = user != null ? user.getName() : "Desenvolvedor";
        String userEmail = user != null ? user.getEmail() : null;

        JoltProjectDto created = joltProjectService.createProject(dto, userId, userName, userEmail);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Atualiza um projeto JOLT existente (opcionalmente gerando nova versão)")
    @PutMapping("/{id}")
    public ResponseEntity<JoltProjectDto> updateProject(
            @PathVariable("id") UUID id,
            @Valid @RequestBody SaveJoltProjectRequestDto dto,
            HttpServletRequest request
    ) {
        User user = resolveUser(request);
        String userId = user != null ? user.getId() : "local-user";
        String userName = user != null ? user.getName() : "Desenvolvedor";

        JoltProjectDto updated = joltProjectService.updateProject(id, dto, userId, userName);
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "Exclui um projeto JOLT e todo seu histórico de versões")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProject(
            @PathVariable("id") UUID id,
            HttpServletRequest request
    ) {
        User user = resolveUser(request);
        String userId = user != null ? user.getId() : "local-user";

        joltProjectService.deleteProject(id, userId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Lista o histórico de versões de um projeto JOLT")
    @GetMapping("/{id}/versions")
    public ResponseEntity<List<JoltProjectVersionDto>> listVersions(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(joltProjectService.listVersions(id));
    }

    @Operation(summary = "Restaura o projeto para uma versão anterior (Rollback)")
    @PostMapping("/{id}/rollback/{versionId}")
    public ResponseEntity<JoltProjectDto> rollback(
            @PathVariable("id") UUID id,
            @PathVariable("versionId") UUID versionId,
            HttpServletRequest request
    ) {
        User user = resolveUser(request);
        String userId = user != null ? user.getId() : "local-user";
        String userName = user != null ? user.getName() : "Desenvolvedor";

        JoltProjectDto restored = joltProjectService.rollbackToVersion(id, versionId, userId, userName);
        return ResponseEntity.ok(restored);
    }
}
