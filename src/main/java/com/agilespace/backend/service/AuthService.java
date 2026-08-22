package com.agilespace.backend.service;

import com.agilespace.backend.domain.ProjectConfig;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.*;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtTokenUtil;
import com.agilespace.backend.security.PasswordUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final ProjectConfigRepository projectConfigRepository;
    private final ProjectMemberRoleRepository projectMemberRoleRepository;
    private final UserProjectResolverService userProjectResolverService;
    private final JwtTokenUtil jwtTokenUtil;

    /**
     * Realiza o login do usuário, valida a senha com PBKDF2 e resolve seus projetos/cargos.
     */
    @Transactional
    public AuthResponseDto login(LoginRequestDto request) {
        String cleanEmail = request.getEmail().trim().toLowerCase();
        User user = userRepository.findByEmail(cleanEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "E-mail ou senha incorretos"));

        if (!user.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Usuário inativo no sistema");
        }

        // Validação da senha
        if (user.getPasswordHash() == null || !PasswordUtil.verifyPassword(request.getPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "E-mail ou senha incorretos");
        }

        // Resolve projetos e lideranças
        UserProjectAccessDto access = userProjectResolverService.resolveUserAccess(user);

        // Se o usuário ainda não tiver um defaultProjectId e possuir projetos associados, define o primeiro
        if (user.getDefaultProjectId() == null && !access.getProjects().isEmpty()) {
            user.setDefaultProjectId(access.getProjects().get(0).getProjectId());
            userRepository.save(user);
            access = userProjectResolverService.resolveUserAccess(user);
        }

        return buildAuthResponse(user, access);
    }

    /**
     * Cadastra um novo usuário no login padrão e faz o auto-link com os papéis do Jira Profields.
     */
    @Transactional
    public AuthResponseDto register(RegisterRequestDto request) {
        String cleanEmail = request.getEmail().trim().toLowerCase();

        if (userRepository.findByEmail(cleanEmail).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este e-mail já está cadastrado");
        }

        String userId = UUID.randomUUID().toString();
        String passwordHash = PasswordUtil.hashPassword(request.getPassword());

        // Busca se a pessoa já possui papéis atribuídos no Profields
        List<ProjectMemberRole> roles = projectMemberRoleRepository.findByEmailIgnoreCase(cleanEmail);
        String defaultProject = request.getDefaultProjectId();
        String segment = request.getSegmentName();
        String tribe = request.getTribeName();

        if (!roles.isEmpty()) {
            ProjectMemberRole primaryRole = roles.get(0);
            if (defaultProject == null || defaultProject.isBlank()) {
                defaultProject = primaryRole.getProjectId();
            }
            Optional<ProjectConfig> optProject = projectConfigRepository.findById(primaryRole.getProjectId());
            if (optProject.isPresent()) {
                if (segment == null || segment.isBlank()) segment = optProject.get().getSegmentName();
                if (tribe == null || tribe.isBlank()) tribe = optProject.get().getTribeName();
            }
        }

        User user = User.builder()
                .id(userId)
                .email(cleanEmail)
                .name(request.getName().trim())
                .passwordHash(passwordHash)
                .authProvider("LOCAL")
                .role("MEMBER")
                .jiraAccountId(request.getJiraAccountId() != null ? request.getJiraAccountId().trim() : null)
                .defaultProjectId(defaultProject)
                .segmentName(segment)
                .tribeName(tribe)
                .active(true)
                .isGuest(false)
                .build();

        user = userRepository.save(user);

        // Atualiza a FK de userId na tabela de papéis do projeto
        for (ProjectMemberRole r : roles) {
            r.setUserId(user.getId());
            projectMemberRoleRepository.save(r);
        }

        UserProjectAccessDto access = userProjectResolverService.resolveUserAccess(user);
        return buildAuthResponse(user, access);
    }

    /**
     * Retorna os dados completos do usuário autenticado (id já validado pelo JwtAuthenticationFilter).
     */
    @Transactional(readOnly = true)
    public AuthResponseDto getMe(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida ou expirada"));

        UserProjectAccessDto access = userProjectResolverService.resolveUserAccess(user);
        return buildAuthResponse(user, access);
    }

    /**
     * Alterna o projeto ativo do usuário autenticado, validando que ele tem acesso ao projeto destino.
     */
    @Transactional
    public AuthResponseDto switchActiveProject(String userId, String newProjectId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado"));

        UserProjectAccessDto currentAccess = userProjectResolverService.resolveUserAccess(user);
        boolean hasAccess = currentAccess.getProjects().stream()
                .anyMatch(p -> p.getProjectId().equalsIgnoreCase(newProjectId));
        if (!hasAccess) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Usuário não tem acesso a este projeto");
        }

        user.setDefaultProjectId(newProjectId);
        user = userRepository.save(user);

        UserProjectAccessDto access = userProjectResolverService.resolveUserAccess(user);
        return buildAuthResponse(user, access);
    }

    private AuthResponseDto buildAuthResponse(User user, UserProjectAccessDto access) {
        String activeProjId = (user.getDefaultProjectId() != null) ? user.getDefaultProjectId() : access.getPrimaryProjectId();
        String activeProjName = "";
        String activeProjRole = "Membro";
        String segment = user.getSegmentName() != null ? user.getSegmentName() : "";
        String tribe = user.getTribeName() != null ? user.getTribeName() : "";

        if (activeProjId != null) {
            Optional<UserProjectAccessDto.ProjectAccessItem> optItem = access.getProjects().stream()
                    .filter(p -> p.getProjectId().equalsIgnoreCase(activeProjId))
                    .findFirst();
            if (optItem.isPresent()) {
                activeProjName = optItem.get().getProjectName();
                activeProjRole = optItem.get().getRoleName();
                if (segment.isBlank()) segment = optItem.get().getSegmentName();
                if (tribe.isBlank()) tribe = optItem.get().getTribeName();
            }
        }

        String token = jwtTokenUtil.generateToken(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getRole(),
                activeProjId,
                segment,
                tribe
        );

        return AuthResponseDto.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresIn(86400L)
                .id(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .role(user.getRole())
                .authProvider(user.getAuthProvider())
                .jiraAccountId(user.getJiraAccountId())
                .avatarSeed(user.getAvatarSeed())
                .activeProjectId(activeProjId)
                .activeProjectName(activeProjName)
                .activeProjectRole(activeProjRole)
                .segmentName(segment)
                .tribeName(tribe)
                .isTransversalLeader(access.isTransversalLeader())
                .accessibleProjects(access.getProjects())
                .build();
    }
}
