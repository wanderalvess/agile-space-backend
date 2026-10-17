package com.agilespace.backend.service;

import com.agilespace.backend.domain.ProjectConfig;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.*;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.SquadMemberRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JiraAccountIdGuard;
import com.agilespace.backend.security.JwtTokenUtil;
import com.agilespace.backend.security.PasswordUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final ProjectConfigRepository projectConfigRepository;
    private final ProjectMemberRoleRepository projectMemberRoleRepository;
    private final SquadMemberRepository squadMemberRepository;
    private final UserProjectResolverService userProjectResolverService;
    private final JwtTokenUtil jwtTokenUtil;
    private final JiraAccountIdGuard jiraAccountIdGuard;

    /**
     * Modelo novo de vínculo (opcionais, fora do construtor: o modo decide se entram em jogo; ausentes = legacy).
     * Ver app.membership.mode em application.yml e docs/membership-api.md.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MembershipService membershipService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MembershipShadowService membershipShadowService;

    /** Opcional (não entra no construtor): só serve para o logout limpar o cache de estado da conta. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.agilespace.backend.security.UserSessionGuard sessionGuard;

    /** Hash de uma senha qualquer, só para gastar o mesmo tempo quando o e-mail não existe (evita enumeração por tempo de resposta). */
    private static volatile String dummyHash;

    private static String dummyHash() {
        String h = dummyHash;
        if (h == null) {
            h = PasswordUtil.hashPassword("senha-inexistente-" + UUID.randomUUID());
            dummyHash = h;
        }
        return h;
    }

    // Vazio (default em application.yml) = autocadastro liberado pra qualquer e-mail — usado em
    // dev/teste, onde os fixtures usam @empresa.com.br. Em produção (application-prod.yml) tem
    // default "totvs.com.br", já que essa ferramenta é interna à TOTVS.
    @Value("${app.security.allowed-email-domain:}")
    private String allowedEmailDomain;

    // Opt-in para fechar o autocadastro (APP_REGISTRATION_ENABLED=false) depois que todo mundo entrou.
    // Padrão true: o comportamento de quem usa hoje não muda.
    @Value("${app.security.registration-enabled:true}")
    private boolean registrationEnabled = true;

    /** Domínios permitidos no autocadastro (lista separada por vírgula, sem "@"). Vazio = qualquer e-mail (dev/teste). */
    List<String> allowedDomains() {
        if (allowedEmailDomain == null || allowedEmailDomain.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(allowedEmailDomain.split(","))
                .map(d -> d.trim().toLowerCase())
                .map(d -> d.startsWith("@") ? d.substring(1) : d)
                .filter(d -> !d.isEmpty())
                .toList();
    }

    /**
     * Casamento exato do domínio: exatamente um "@" e o que vem depois dele igual a um domínio da lista.
     * Evita "evil-totvs.com.br" (endsWith frouxo) e "a@x.com@totvs.com.br".
     */
    boolean isEmailDomainAllowed(String email) {
        List<String> domains = allowedDomains();
        if (domains.isEmpty()) {
            return true;
        }
        if (email == null) {
            return false;
        }
        String clean = email.trim().toLowerCase();
        int at = clean.indexOf('@');
        if (at <= 0 || at != clean.lastIndexOf('@')) {
            return false;
        }
        return domains.contains(clean.substring(at + 1));
    }

    private String describeAllowedDomains() {
        return String.join(", ", allowedDomains().stream().map(d -> "@" + d).toList());
    }

    /**
     * Realiza o login do usuário, valida a senha com PBKDF2 e resolve seus projetos/cargos.
     */
    @Transactional
    public AuthResponseDto login(LoginRequestDto request) {
        String identifier = request.getEmail().trim().toLowerCase();
        User user = findUserByIdentifier(identifier).orElse(null);
        if (user == null) {
            PasswordUtil.verifyPassword(request.getPassword(), dummyHash());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "E-mail ou senha incorretos");
        }
        String cleanEmail = user.getEmail();

        // Validação da senha antes de revelar que a conta está inativa
        if (user.getPasswordHash() == null || !PasswordUtil.verifyPassword(request.getPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "E-mail ou senha incorretos");
        }

        if (!user.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Usuário inativo no sistema");
        }

        user.setLastLoginAt(java.time.LocalDateTime.now());
        userRepository.save(user);

        // Resolve projetos e lideranças
        Resolved resolved = resolveAccess(user);
        boolean rosterFallback = false;

        // Se o usuário ainda não tiver um defaultProjectId e possuir projetos associados, define o primeiro
        if (user.getDefaultProjectId() == null && !resolved.access().getProjects().isEmpty()) {
            user.setDefaultProjectId(resolved.access().getProjects().get(0).getProjectId());
            if (user.getSquadId() == null || user.getSquadId().isBlank()) {
                user.setSquadId(resolved.access().getProjects().get(0).getProjectId());
            }
            userRepository.save(user);
            resolved = resolveAccess(user);
        } else if ((user.getSquadId() == null || user.getSquadId().isBlank()) && user.getDefaultProjectId() != null) {
            user.setSquadId(user.getDefaultProjectId());
            userRepository.save(user);
        } else if (user.getSquadId() == null || user.getSquadId().isBlank()) {
            List<SquadMember> matchedMembers = squadMemberRepository.findByUserIdentifier(cleanEmail);
            if (!matchedMembers.isEmpty()) {
                rosterFallback = true;
                user.setDefaultProjectId(matchedMembers.get(0).getSquadId());
                user.setSquadId(matchedMembers.get(0).getSquadId());
                userRepository.save(user);
                for (SquadMember sm : matchedMembers) {
                    if (sm.getClaimedByUid() == null) {
                        sm.setClaimedByUid(user.getId());
                        squadMemberRepository.save(sm);
                    }
                }
                resolved = resolveAccess(user);
            }
        }

        if (resolved.access().getProjects().isEmpty()) {
            // Sessão sem nenhuma linha em project_member_roles. Antes isso passava em silêncio (principalmente o fallback
            // por roster acima, que grava squad_id/default_project_id sem criar vínculo). Não bloqueia o login: fica
            // registrado e, no modelo novo, /me/memberships e a sessão trazem legacyOnly=true. Conta nova, sem rastro
            // nenhum, só vai para o onboarding (não é "legado").
            boolean traces = rosterFallback || hasSquadTrace(user.getSquadId()) || hasSquadTrace(user.getDefaultProjectId());
            if (traces) {
                log.warn("Login sem vínculo em project_member_roles (só roster/squad_id antigo): user={} roster_fallback={}",
                        user.getId(), rosterFallback);
            } else {
                log.debug("Login de conta sem vínculo e sem rastro do modelo antigo: user={}", user.getId());
            }
        }

        return buildAuthResponse(user, resolved);
    }

    private static boolean hasSquadTrace(String squad) {
        return squad != null && !squad.isBlank()
                && !"Sem Time".equalsIgnoreCase(squad.trim()) && !"Sem Squad".equalsIgnoreCase(squad.trim());
    }

    /**
     * Aceita e-mail completo ou só o usuário (parte antes do "@"). Usuário que bate com mais de uma
     * conta (domínios diferentes) é ambíguo e tratado como não encontrado — exige o e-mail completo.
     */
    private Optional<User> findUserByIdentifier(String identifier) {
        if (identifier.contains("@")) {
            return userRepository.findByEmail(identifier);
        }
        if (identifier.isBlank()) {
            return Optional.empty();
        }
        List<User> matches = userRepository.findByEmailStartingWithIgnoreCase(identifier + "@");
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    /**
     * Cadastra um novo usuário no login padrão e faz o auto-link com os papéis do Jira Profields.
     * Se o e-mail já pertence a uma conta pré-provisionada pelo sync do Jira (ainda sem senha —
     * ver JiraAdminService.confirmSync), reivindica essa conta em vez de bloquear o cadastro.
     */
    @Transactional
    public AuthResponseDto register(RegisterRequestDto request) {
        String cleanEmail = request.getEmail().trim().toLowerCase();

        if (!registrationEnabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "O cadastro está fechado. Peça acesso a um administrador.");
        }

        if (!isEmailDomainAllowed(cleanEmail)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Cadastro restrito a e-mails corporativos: " + describeAllowedDomains());
        }

        Optional<User> existingUser = userRepository.findByEmail(cleanEmail);
        if (existingUser.isPresent() && existingUser.get().getPasswordHash() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este e-mail já está cadastrado");
        }

        String passwordHash = PasswordUtil.hashPassword(request.getPassword());

        // Id do Jira digitado no cadastro só vale se não for de outra pessoa do roster (ele liga a conta
        // a equipes e liderança). Se for, o cadastro segue sem ele; o vínculo certo é o "Sou eu".
        String requestedJiraId = request.getJiraAccountId() != null ? request.getJiraAccountId().trim() : null;
        if (requestedJiraId != null && !requestedJiraId.isEmpty() && jiraAccountIdGuard != null
                && !jiraAccountIdGuard.isFreeFor(requestedJiraId, existingUser.map(User::getId).orElse(null), cleanEmail)) {
            log.warn("Cadastro de {} ignorou jiraAccountId que pertence a outra pessoa do roster", cleanEmail);
            requestedJiraId = null;
        }

        // Busca se a pessoa já possui papéis atribuídos no Profields
        List<ProjectMemberRole> roles = projectMemberRoleRepository.findByEmailIgnoreCase(cleanEmail);
        List<SquadMember> squadMembers = squadMemberRepository.findByUserIdentifier(cleanEmail);
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
        } else if (!squadMembers.isEmpty()) {
            SquadMember primarySquadMember = squadMembers.get(0);
            if (defaultProject == null || defaultProject.isBlank()) {
                defaultProject = primarySquadMember.getSquadId();
            }
        }

        User user;
        if (existingUser.isPresent()) {
            // Conta-fantasma criada pelo sync do Jira (sem senha): reivindica em vez de criar outra.
            user = existingUser.get();
            user.setPasswordHash(passwordHash);
            user.setAuthProvider("LOCAL");
            user.setActive(true);
            if (user.getName() == null || user.getName().isBlank()) {
                user.setName(request.getName().trim());
            }
            if (requestedJiraId != null && !requestedJiraId.isEmpty()
                    && (user.getJiraAccountId() == null || user.getJiraAccountId().isBlank())) {
                user.setJiraAccountId(requestedJiraId);
            }
            if (user.getDefaultProjectId() == null || user.getDefaultProjectId().isBlank()) {
                user.setDefaultProjectId(defaultProject);
            }
            if (user.getSquadId() == null || user.getSquadId().isBlank()) {
                user.setSquadId(defaultProject);
            }
            if (user.getSegmentName() == null || user.getSegmentName().isBlank()) {
                user.setSegmentName(segment);
            }
            if (user.getTribeName() == null || user.getTribeName().isBlank()) {
                user.setTribeName(tribe);
            }
        } else {
            user = User.builder()
                    .id(UUID.randomUUID().toString())
                    .email(cleanEmail)
                    .name(request.getName().trim())
                    .passwordHash(passwordHash)
                    .authProvider("LOCAL")
                    .role("MEMBER")
                    .jiraAccountId(requestedJiraId != null && !requestedJiraId.isEmpty() ? requestedJiraId : null)
                    .defaultProjectId(defaultProject)
                    .squadId(defaultProject)
                    .segmentName(segment)
                    .tribeName(tribe)
                    .active(true)
                    .isGuest(false)
                    .build();
        }

        user = userRepository.save(user);

        // Atualiza a FK de userId na tabela de papéis do projeto
        for (ProjectMemberRole r : roles) {
            r.setUserId(user.getId());
            projectMemberRoleRepository.save(r);
        }

        // Auto-vincula squad_members com o id do usuário recém-registrado
        for (SquadMember sm : squadMembers) {
            sm.setClaimedByUid(user.getId());
            squadMemberRepository.save(sm);
        }

        return buildAuthResponse(user, resolveAccess(user));
    }

    /**
     * Retorna os dados completos do usuário autenticado (id já validado pelo JwtAuthenticationFilter).
     */
    @Transactional(readOnly = true)
    public AuthResponseDto getMe(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida ou expirada"));

        return buildAuthResponse(user, resolveAccess(user));
    }

    /**
     * Alterna o projeto ativo do usuário autenticado, validando que ele tem acesso ao projeto destino.
     */
    @Transactional
    public AuthResponseDto switchActiveProject(String userId, String newProjectId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado"));

        // Valida contra os vínculos do modo ativo (legacy/shadow: resolvedor antigo; new: MembershipService). Nunca afrouxa:
        // sem vínculo na squad pedida, 403.
        UserProjectAccessDto currentAccess = resolveAccess(user).access();
        UserProjectAccessDto.ProjectAccessItem target = currentAccess.getProjects().stream()
                .filter(p -> p.getProjectId().equalsIgnoreCase(newProjectId))
                .findFirst().orElse(null);
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Usuário não tem acesso a este projeto");
        }

        // Dual-write continua (default_project_id e squad_id). No modelo novo grava o id canônico do vínculo (o legado
        // gravava o texto como veio, inclusive com outra caixa).
        String toStore = membershipMode() == MembershipMode.NEW ? target.getProjectId() : newProjectId;
        user.setDefaultProjectId(toStore);
        user.setSquadId(toStore);
        user = userRepository.save(user);
        if (membershipService != null) {
            membershipService.evict(userId);
        }

        return buildAuthResponse(user, resolveAccess(user));
    }

    private AuthResponseDto buildAuthResponse(User user, Resolved resolved) {
        return buildAuthResponse(user, resolved, Instant.now().getEpochSecond());
    }

    /** Vínculos que alimentam a sessão: o access (accessibleProjects), a squad ativa e, no modelo novo, legacyOnly. */
    private record Resolved(UserProjectAccessDto access, String activeProjectId, Boolean legacyOnly) { }

    private MembershipMode membershipMode() {
        return membershipService == null ? MembershipMode.LEGACY : membershipService.mode();
    }

    /**
     * Resolve os vínculos conforme app.membership.mode. LEGACY e SHADOW devolvem exatamente o que o resolvedor antigo
     * devolvia (a squad ativa continua sendo users.default_project_id sem validar); SHADOW ainda compara com o modelo
     * novo e registra as divergências. NEW usa o {@link MembershipService}: squad ativa validada contra os vínculos.
     */
    private Resolved resolveAccess(User user) {
        MembershipMode mode = membershipMode();
        if (mode == MembershipMode.NEW) {
            MembershipService.View view = membershipService.forUser(user);
            return new Resolved(MembershipService.toAccess(user, view), view.activeProjectId(), view.legacyOnly() ? Boolean.TRUE : null);
        }
        UserProjectAccessDto legacy = userProjectResolverService.resolveUserAccess(user);
        String active = user.getDefaultProjectId() != null ? user.getDefaultProjectId() : legacy.getPrimaryProjectId();
        if (mode == MembershipMode.SHADOW && membershipShadowService != null) {
            membershipShadowService.record(user, legacy, active);
        }
        return new Resolved(legacy, active, null);
    }

    /**
     * Renova a sessão: emite um token novo (mais 24 h por padrão) preservando o instante do login original.
     * Depois do teto absoluto (app.security.jwt-max-session-seconds, 7 dias) é preciso entrar de novo.
     */
    @Transactional(readOnly = true)
    public AuthResponseDto refresh(String userId, long sessionStartEpochSeconds) {
        long now = Instant.now().getEpochSecond();
        if (sessionStartEpochSeconds <= 0 || now - sessionStartEpochSeconds > jwtTokenUtil.getMaxSessionSeconds()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão expirada: entre novamente");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida ou expirada"));
        if (!user.isActive()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida: conta inativa");
        }
        return buildAuthResponse(user, resolveAccess(user), sessionStartEpochSeconds);
    }

    /**
     * Troca a própria senha (exige a atual). As outras sessões abertas caem; a resposta traz um token novo
     * para a sessão de quem trocou continuar.
     */
    @Transactional
    public AuthResponseDto changePassword(String userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida ou expirada"));
        if (user.getPasswordHash() == null || !PasswordUtil.verifyPassword(currentPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Senha atual incorreta");
        }
        if (currentPassword.equals(newPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A nova senha precisa ser diferente da atual");
        }
        user.setPasswordHash(PasswordUtil.hashPassword(newPassword));
        user.setTokensValidAfter(Instant.now().getEpochSecond());
        user = userRepository.save(user);
        if (sessionGuard != null) {
            sessionGuard.evict(userId);
        }
        return buildAuthResponse(user, resolveAccess(user));
    }

    /** Encerra a sessão no servidor: tokens emitidos até agora (de qualquer aparelho) deixam de valer. */
    @Transactional
    public void logout(String userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setTokensValidAfter(Instant.now().getEpochSecond());
            userRepository.save(user);
            if (sessionGuard != null) {
                sessionGuard.evict(userId);
            }
        });
    }

    private AuthResponseDto buildAuthResponse(User user, Resolved resolved, long authAt) {
        UserProjectAccessDto access = resolved.access();
        String activeProjId = resolved.activeProjectId();
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
                tribe,
                authAt
        );

        return AuthResponseDto.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresIn(jwtTokenUtil.getExpirationSeconds())
                .id(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .role(user.getRole())
                .jobTitle(user.getJobTitle())
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
                .legacyOnly(resolved.legacyOnly())
                .build();
    }
}
