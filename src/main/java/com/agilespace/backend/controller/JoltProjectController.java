package com.agilespace.backend.controller;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.JoltProjectDto;
import com.agilespace.backend.dto.JoltProjectVersionDto;
import com.agilespace.backend.dto.SaveJoltProjectRequestDto;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.JoltProjectService;
import com.agilespace.backend.service.JoltProjectService.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
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

    /** Identidade vem do JWT (atributos do filtro); o cadastro só complementa nome, e-mail e squad. */
    private Caller caller(HttpServletRequest request) {
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Autenticação necessária");
        }
        String role = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
        String email = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_EMAIL);
        User user = userRepository.findById(userId).orElse(null);
        return new Caller(
                userId,
                role,
                user != null ? user.getSquadId() : null,
                user != null ? user.getName() : null,
                user != null && user.getEmail() != null ? user.getEmail() : email);
    }

    @Operation(summary = "Lista projetos JOLT acessíveis ao usuário")
    @GetMapping
    public ResponseEntity<List<JoltProjectDto>> listProjects(
            @RequestParam(value = "search", required = false) String search,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(joltProjectService.listProjects(caller(request), search));
    }

    @Operation(summary = "Obtém detalhes completos de um projeto JOLT")
    @GetMapping("/{id}")
    public ResponseEntity<JoltProjectDto> getProject(@PathVariable("id") UUID id, HttpServletRequest request) {
        return ResponseEntity.ok(joltProjectService.getProject(id, caller(request)));
    }

    @Operation(summary = "Cria um novo projeto JOLT com versão inicial v1")
    @PostMapping
    public ResponseEntity<JoltProjectDto> createProject(
            @Valid @RequestBody SaveJoltProjectRequestDto dto,
            HttpServletRequest request
    ) {
        JoltProjectDto created = joltProjectService.createProject(dto, caller(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Atualiza um projeto JOLT existente (opcionalmente gerando nova versão)")
    @PutMapping("/{id}")
    public ResponseEntity<JoltProjectDto> updateProject(
            @PathVariable("id") UUID id,
            @Valid @RequestBody SaveJoltProjectRequestDto dto,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(joltProjectService.updateProject(id, dto, caller(request)));
    }

    @Operation(summary = "Exclui um projeto JOLT e todo seu histórico de versões")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProject(
            @PathVariable("id") UUID id,
            HttpServletRequest request
    ) {
        joltProjectService.deleteProject(id, caller(request));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Lista o histórico de versões de um projeto JOLT")
    @GetMapping("/{id}/versions")
    public ResponseEntity<List<JoltProjectVersionDto>> listVersions(@PathVariable("id") UUID id, HttpServletRequest request) {
        return ResponseEntity.ok(joltProjectService.listVersions(id, caller(request)));
    }

    @Operation(summary = "Restaura o projeto para uma versão anterior (Rollback)")
    @PostMapping("/{id}/rollback/{versionId}")
    public ResponseEntity<JoltProjectDto> rollback(
            @PathVariable("id") UUID id,
            @PathVariable("versionId") UUID versionId,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(joltProjectService.rollbackToVersion(id, versionId, caller(request)));
    }

    /** Gravação concorrente do mesmo projeto (@Version): o cliente recarrega e tenta de novo. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "CONFLICT",
                "message", "Este projeto foi alterado em outra aba ou por outra pessoa. Recarregue antes de salvar para não perder alterações."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrity(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "BAD_REQUEST",
                "message", "Dados inválidos ou fora dos limites permitidos."));
    }
}
