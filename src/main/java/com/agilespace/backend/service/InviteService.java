package com.agilespace.backend.service;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.domain.Invite;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.repository.InviteRepository;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.LeadPolicy;
import com.agilespace.backend.security.SquadLeadership;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Convite real (token/link) pra vincular um usuário a um squad + papel de negócio,
 * sem depender do e-mail bater exatamente com o que veio do Jira (ver JiraAdminService/
 * JiraProfieldsService/AuthService, que só linkam por match exato de e-mail).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InviteService {

    private static final int EXPIRATION_DAYS = 7;
    static final int MAX_ROLE_NAME = 80;

    // Convite de equipe (multiuso). Limites padrão; o teto de pessoas é configurável (app.invites.team.max-uses).
    static final int TEAM_DEFAULT_VALIDITY_DAYS = 7;
    static final int TEAM_MAX_VALIDITY_DAYS = 30;

    @org.springframework.beans.factory.annotation.Value("${app.invites.team.max-uses:200}")
    private int teamMaxUses = 200;

    private final InviteRepository inviteRepository;
    private final AuditLogRepository auditLogRepository;
    private final SquadService squadService;
    private final ProjectConfigRepository projectConfigRepository;
    private final ProjectMemberRoleRepository projectMemberRoleRepository;
    private final UserRepository userRepository;

    /** Quem entrou por convite de equipe (opcional: montagem manual em testes antigos segue sem ele). */
    private com.agilespace.backend.repository.InviteRedemptionRepository redemptionRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRedemptionRepository(com.agilespace.backend.repository.InviteRedemptionRepository redemptionRepository) {
        this.redemptionRepository = redemptionRepository;
    }

    /** Gatilhos de notificação (opcional: testes que montam o serviço à mão seguem sem eles). */
    private NotificationTriggers notificationTriggers;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setNotificationTriggers(NotificationTriggers notificationTriggers) {
        this.notificationTriggers = notificationTriggers;
    }

    /** Chave e regra nova da autorização por squad (Etapa 2). Opcional: sem elas só existe o legado. */
    private SquadAccessService squadAccessService;
    private AuthzShadow authzShadow;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSquadAuthz(SquadAccessService squadAccessService, AuthzShadow authzShadow) {
        this.squadAccessService = squadAccessService;
        this.authzShadow = authzShadow;
    }

    /** Etapa 4 (app.membership.lead-level). Montagem manual: legacy (LEAD concede, como antes). */
    private LeadPolicy leadPolicy = LeadPolicy.LEGACY;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLeadPolicy(LeadPolicy leadPolicy) {
        if (leadPolicy != null) this.leadPolicy = leadPolicy;
    }

    @Transactional
    public Invite createInvite(String squadId, String roleName, String email, String invitedByUserId) {
        roleName = roleName == null ? "" : roleName.trim();
        if (roleName.isEmpty() || roleName.length() > MAX_ROLE_NAME) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o papel do convite (até " + MAX_ROLE_NAME + " caracteres).");
        }
        // Convite vincula contribuidor. Papel de liderança (Agile Master, Tech Lead...) vem do Jira ou de um admin:
        // a roleKey derivada do nome decide quem gerencia equipes, então aceitar o nome aqui seria escalada de privilégio.
        if (SquadLeadership.isLeadershipRoleName(roleName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Convite não concede papel de liderança. Convide como Developer, QA, UX/Designer ou SME; liderança vem do Jira ou de um administrador.");
        }
        if (email != null && !email.isBlank() && (email.length() > 254 || !email.contains("@"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "E-mail do convite inválido.");
        }
        Invite invite = Invite.builder()
                .id(UUID.randomUUID().toString())
                .token(generateToken())
                .squadId(squadId)
                .roleName(roleName)
                .email(email != null && !email.isBlank() ? email.trim().toLowerCase() : null)
                .invitedBy(invitedByUserId)
                .status("PENDING")
                .expiresAt(LocalDateTime.now().plusDays(EXPIRATION_DAYS))
                .build();
        invite = inviteRepository.save(invite);
        if (notificationTriggers != null) {
            notificationTriggers.inviteCreated(invite.getToken(), squadId, invite.getSquadName(), invite.getEmail(), invitedByUserId);
        }

        audit("INVITE_CREATED", invitedByUserId, "Convite criado para squad " + squadId + " (papel: " + roleName + ")"
                + (invite.getEmail() != null ? ", e-mail: " + invite.getEmail() : ""));
        log.info("Convite {} criado para squad {} por {}", invite.getId(), squadId, invitedByUserId);
        return invite;
    }

    @Transactional(readOnly = true)
    public List<Invite> listPending(String squadId) {
        return inviteRepository.findBySquadIdOrderByCreatedAtDesc(squadId);
    }

    @Transactional(readOnly = true)
    public Invite getByToken(String token) {
        return inviteRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Convite não encontrado"));
    }

    /**
     * Para a página do convite. Quem vê o quê:
     * - destinatário (e-mail do convite = e-mail do logado), membro/liderança da squad ou admin: o convite completo;
     * - qualquer outro logado, convite de link aberto (sem e-mail): só o necessário pra aceitar (squad, papel, status,
     *   validade) — o token é a credencial de um link aberto. E-mail do convite e quem convidou ficam ocultos;
     * - qualquer outro logado, convite endereçado a um e-mail: 404 genérico, sem confirmar que o convite existe.
     */
    @Transactional(readOnly = true)
    public Invite getForDisplay(String token, User caller) {
        Invite invite = getByToken(token);
        boolean privileged = caller != null && (isRecipient(invite, caller) || isSquadMemberOrAdmin(invite.getSquadId(), caller));
        if (!privileged && invite.getEmail() != null && !invite.getEmail().isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Convite não encontrado");
        }
        String squadName = projectConfigRepository.findById(invite.getSquadId())
                .map(p -> p.getName())
                .filter(n -> n != null && !n.isBlank())
                .orElse(null);
        Invite view = Invite.builder()
                .id(invite.getId())
                .token(invite.getToken())
                .squadId(invite.getSquadId())
                .roleName(invite.getRoleName())
                .email(privileged ? invite.getEmail() : null)
                .invitedBy(privileged ? invite.getInvitedBy() : null)
                .status(invite.getStatus())
                .createdAt(invite.getCreatedAt())
                .expiresAt(invite.getExpiresAt())
                .acceptedAt(privileged ? invite.getAcceptedAt() : null)
                .acceptedByUserId(privileged ? invite.getAcceptedByUserId() : null)
                .build();
        view.setSquadName(squadName);
        decorateTeamView(view, invite, caller, privileged);
        return view;
    }

    /**
     * Pré-visualização SEM login, para a página do convite mostrar o que é antes de a pessoa entrar ou criar a conta.
     * O token é a credencial do link (UUID de 128 bits) e o aceite continua exigindo sessão e, se houver, o e-mail
     * certo. Devolve só o necessário: squad, papel, validade (o nome de quem convidou não sai nesta rota pública) e situação. O e-mail do
     * destinatário nunca sai inteiro: só mascarado (c••••@dominio), para a pessoa saber com qual conta entrar.
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> getPublicPreview(String token) {
        Invite invite = getByToken(token);
        String squadName = projectConfigRepository.findById(invite.getSquadId())
                .map(p -> p.getName())
                .filter(n -> n != null && !n.isBlank())
                .orElse(null);
        String status = invite.isMultiUse() ? teamStatus(invite, LocalDateTime.now()) : invite.getStatus();
        if (!invite.isMultiUse() && "PENDING".equalsIgnoreCase(status) && invite.getExpiresAt().isBefore(LocalDateTime.now())) {
            status = "EXPIRED";
        }
        java.util.Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("squadId", invite.getSquadId());
        view.put("squadName", squadName);
        view.put("roleName", invite.getRoleName());
        view.put("status", status);
        view.put("expiresAt", invite.getExpiresAt());
        view.put("emailMasked", maskEmail(invite.getEmail()));
        view.put("multiUse", invite.isMultiUse());
        view.put("remainingUses", remainingUses(invite));
        return view;
    }

    static String maskEmail(String email) {
        if (email == null || email.isBlank() || !email.contains("@")) return null;
        int at = email.indexOf('@');
        return email.charAt(0) + "••••" + email.substring(at);
    }

    private static boolean isRecipient(Invite invite, User caller) {
        return invite.getEmail() != null && caller.getEmail() != null && invite.getEmail().trim().equalsIgnoreCase(caller.getEmail().trim());
    }

    private boolean isSquadMemberOrAdmin(String squadId, User caller) {
        if (squadAccessService == null || authzShadow == null || authzShadow.isLegacy()) {
            return legacyIsSquadMemberOrAdmin(squadId, caller);
        }
        // Etapa 2 (app.membership.authz): na regra nova só o vínculo (pmr, inclusive tribo/segmento) conta; users.squad_id deixa de valer.
        return authzShadow.decide("inviteView", caller.getId(), squadId,
                () -> legacyIsSquadMemberOrAdmin(squadId, caller),
                () -> leadPolicy.isPrivileged(caller.getRole())
                        || squadAccessService.isLinkedToSquad(caller, squadId),
                legacyAllowed -> legacyAllowed
                        ? (squadId.equalsIgnoreCase(caller.getDefaultProjectId())
                                ? "ONLY_SQUAD_ID" : "RESOLVER_ONLY")
                        : "NEW_ONLY_MEMBERSHIP");
    }

    private boolean legacyIsSquadMemberOrAdmin(String squadId, User caller) {
        if (leadPolicy.isPrivileged(caller.getRole())) return true;
        if (squadId.equalsIgnoreCase(caller.getDefaultProjectId())) return true;
        List<ProjectMemberRole> rows = projectMemberRoleRepository.findByProjectId(squadId);
        return rows != null && rows.stream().anyMatch(m ->
                (caller.getId() != null && caller.getId().equals(m.getUserId()))
                || (caller.getEmail() != null && m.getEmail() != null && caller.getEmail().trim().equalsIgnoreCase(m.getEmail().trim())));
    }

    @Transactional
    public void revoke(String id, String squadId, String revokedBy) {
        Invite invite = inviteRepository.findById(id)
                .filter(i -> i.getSquadId().equalsIgnoreCase(squadId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Convite não encontrado"));
        // Convite já aceito é histórico: marcá-lo como revogado apagaria o registro de quem entrou.
        if (!"PENDING".equalsIgnoreCase(invite.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Só convites pendentes podem ser revogados");
        }
        invite.setStatus("REVOKED");
        invite.setRevokedAt(LocalDateTime.now());
        inviteRepository.save(invite);
        audit("INVITE_REVOKED", revokedBy, "Convite " + id + " revogado");
    }

    @Transactional
    public SquadMember acceptInvite(String token, User acceptingUser) {
        Invite invite = inviteRepository.findByTokenForUpdate(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Convite não encontrado"));
        if (invite.isMultiUse()) {
            return acceptTeamInvite(invite, acceptingUser);
        }

        if (!"PENDING".equalsIgnoreCase(invite.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Este convite já foi usado ou revogado");
        }
        if (invite.getExpiresAt().isBefore(LocalDateTime.now())) {
            invite.setStatus("EXPIRED");
            inviteRepository.save(invite);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Este convite expirou");
        }
        if (invite.getEmail() != null && !invite.getEmail().equalsIgnoreCase(acceptingUser.getEmail())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Este convite foi endereçado a outro e-mail");
        }
        // Convites criados antes da regra "convite não concede liderança" ainda podem existir pendentes.
        if (SquadLeadership.isLeadershipRoleName(invite.getRoleName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Este convite é de um papel de liderança, que não é mais concedido por convite. Peça um novo link a quem te convidou.");
        }

        // NUNCA grava em user.role a partir daqui: esse campo é o tier de autorização do
        // sistema (ver User.role/UserRole) e aceitar convite de squad não deve poder elevar
        // o acesso admin de alguém — só vincula squad + papel de negócio (mesma invariante
        // documentada em JiraAdminService/SquadService).
        String jiraAccountId = acceptingUser.getJiraAccountId() != null && !acceptingUser.getJiraAccountId().isBlank()
                ? acceptingUser.getJiraAccountId()
                : acceptingUser.getId();

        SquadMember member = SquadMember.builder()
                .displayName(acceptingUser.getName())
                .email(acceptingUser.getEmail())
                .role(invite.getRoleName())
                .claimedByUid(acceptingUser.getId())
                .build();
        SquadMember saved = squadService.saveMember(invite.getSquadId(), jiraAccountId, member);
        linkProjectMembership(invite, acceptingUser);

        invite.setStatus("ACCEPTED");
        invite.setAcceptedAt(LocalDateTime.now());
        invite.setAcceptedByUserId(acceptingUser.getId());
        inviteRepository.save(invite);

        audit("INVITE_ACCEPTED", acceptingUser.getEmail(),
                "Convite " + invite.getId() + " aceito, vinculado ao squad " + invite.getSquadId() + " como " + invite.getRoleName());
        log.info("Convite {} aceito por {} (squad {})", invite.getId(), acceptingUser.getEmail(), invite.getSquadId());
        return saved;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Convite de equipe (multiuso): um link, várias pessoas, papel fixo, sem e-mail.
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Cria o link de equipe. {@code maxUses == null} = sem limite, só para ADMIN; senão 1..teto configurado.
     * Validade em dias (null = 7), máximo 30. Papel de liderança é proibido, como no convite individual.
     */
    @Transactional
    public Invite createTeamInvite(String squadId, String roleName, Integer maxUses, Integer validityDays, String invitedByUserId, boolean admin) {
        roleName = roleName == null ? "" : roleName.trim();
        if (roleName.isEmpty() || roleName.length() > MAX_ROLE_NAME) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o papel do convite (até " + MAX_ROLE_NAME + " caracteres).");
        }
        if (SquadLeadership.isLeadershipRoleName(roleName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Convite não concede papel de liderança. Convide como Developer, QA, UX/Designer ou SME; liderança vem do Jira ou de um administrador.");
        }
        if (maxUses == null) {
            if (!admin) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Só administradores podem criar um link sem limite de pessoas. Informe um limite.");
            }
        } else if (maxUses < 1 || maxUses > teamMaxUses) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O limite de pessoas deve ficar entre 1 e " + teamMaxUses + ".");
        }
        int days = validityDays == null ? TEAM_DEFAULT_VALIDITY_DAYS : validityDays;
        if (days < 1 || days > TEAM_MAX_VALIDITY_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A validade deve ficar entre 1 e " + TEAM_MAX_VALIDITY_DAYS + " dias.");
        }
        Invite invite = Invite.builder()
                .id(UUID.randomUUID().toString())
                .token(generateToken())
                .squadId(squadId)
                .roleName(roleName)
                .invitedBy(invitedByUserId)
                .status("PENDING")
                .multiUse(true)
                .maxUses(maxUses)
                .usesCount(0)
                .expiresAt(LocalDateTime.now().plusDays(days))
                .build();
        invite = inviteRepository.save(invite);
        audit("INVITE_CREATED", invitedByUserId, "origem=INVITE; squad=" + squadId + "; papel=" + roleName + "; conviteId=" + invite.getId()
                + "; multiuso=true; limite=" + (maxUses == null ? "sem limite" : maxUses) + "; validadeDias=" + days);
        log.info("Convite de equipe {} criado para squad {} por {}", invite.getId(), squadId, invitedByUserId);
        return invite;
    }

    /** Quem entrou por um convite de equipe da squad (nome e data), para quem o criou. */
    @Transactional(readOnly = true)
    public List<java.util.Map<String, Object>> listRedemptions(String id, String squadId) {
        Invite invite = inviteRepository.findById(id)
                .filter(i -> i.getSquadId().equalsIgnoreCase(squadId) && i.isMultiUse())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Convite não encontrado"));
        List<com.agilespace.backend.domain.InviteRedemption> rows = redemptionRepository.findByInviteIdOrderByCreatedAtAsc(invite.getId());
        java.util.Map<String, User> users = new java.util.HashMap<>();
        userRepository.findAllById(rows.stream().map(com.agilespace.backend.domain.InviteRedemption::getUserId).toList())
                .forEach(u -> users.put(u.getId(), u));
        List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (com.agilespace.backend.domain.InviteRedemption r : rows) {
            User u = users.get(r.getUserId());
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("userId", r.getUserId());
            item.put("name", u != null && u.getName() != null && !u.getName().isBlank() ? u.getName() : (u != null ? u.getEmail() : null));
            item.put("joinedAt", r.getCreatedAt());
            out.add(item);
        }
        return out;
    }

    /**
     * Aceite do link de equipe. A linha do convite já vem travada (findByTokenForUpdate) e o uso é consumido por UPDATE
     * condicional: mesmo sem a trava, só entra quem conseguir incrementar uses_count dentro do teto. A mesma conta nunca
     * consome dois usos (já usou este convite ou já é da squad: devolve sem consumir).
     */
    private SquadMember acceptTeamInvite(Invite invite, User user) {
        LocalDateTime now = LocalDateTime.now();
        if ("REVOKED".equalsIgnoreCase(invite.getStatus()) || invite.getRevokedAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Este convite foi revogado por quem o enviou.");
        }
        if (invite.getExpiresAt().isBefore(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Este convite expirou");
        }
        if (SquadLeadership.isLeadershipRoleName(invite.getRoleName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Este convite é de um papel de liderança, que não é concedido por convite.");
        }
        // Mesma conta não gasta dois usos: já usou este link, ou já é da squad.
        if (redemptionRepository.existsByInviteIdAndUserId(invite.getId(), user.getId()) || isLinkedToSquad(invite.getSquadId(), user)) {
            return SquadMember.builder().squadId(invite.getSquadId()).role(invite.getRoleName()).claimedByUid(user.getId()).build();
        }
        if (inviteRepository.consumeUse(invite.getId(), now) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este convite atingiu o limite de pessoas. Peça um novo link a quem convidou.");
        }
        redemptionRepository.saveAndFlush(com.agilespace.backend.domain.InviteRedemption.builder()
                .id(UUID.randomUUID().toString()).inviteId(invite.getId()).userId(user.getId()).createdAt(now).build());

        String jiraAccountId = user.getJiraAccountId() != null && !user.getJiraAccountId().isBlank() ? user.getJiraAccountId() : user.getId();
        SquadMember saved = squadService.saveMember(invite.getSquadId(), jiraAccountId, SquadMember.builder()
                .displayName(user.getName()).email(user.getEmail()).role(invite.getRoleName()).claimedByUid(user.getId()).build());
        linkProjectMembership(invite, user);

        // Mesma convenção do aceite individual (performed_by = id do usuário; só o id do convite, nunca o token).
        audit("INVITE_ACCEPTED", user.getId(),
                "origem=INVITE; userId=" + user.getId() + "; email=" + user.getEmail()
                        + "; squad=" + invite.getSquadId() + "; papel=" + invite.getRoleName()
                        + "; conviteId=" + invite.getId() + "; convidadoPor=" + invite.getInvitedBy() + "; multiuso=true");
        log.info("Convite de equipe {} usado por {} (squad {})", invite.getId(), user.getEmail(), invite.getSquadId());
        return saved;
    }

    private boolean isLinkedToSquad(String squadId, User user) {
        List<ProjectMemberRole> rows = projectMemberRoleRepository.findByProjectId(squadId);
        return rows != null && rows.stream().anyMatch(m ->
                (user.getId() != null && user.getId().equals(m.getUserId()))
                || (user.getEmail() != null && m.getEmail() != null && user.getEmail().trim().equalsIgnoreCase(m.getEmail().trim())));
    }

    /** PENDING / EXHAUSTED / EXPIRED / REVOKED de um convite de equipe (derivado; o banco guarda PENDING até revogar). */
    static String teamStatus(Invite invite, LocalDateTime now) {
        if ("REVOKED".equalsIgnoreCase(invite.getStatus()) || invite.getRevokedAt() != null) return "REVOKED";
        if (invite.getExpiresAt().isBefore(now)) return "EXPIRED";
        if (invite.getMaxUses() != null && invite.getUsesCount() >= invite.getMaxUses()) return "EXHAUSTED";
        return "PENDING";
    }

    private static Integer remainingUses(Invite invite) {
        if (invite.isMultiUse()) {
            return invite.getMaxUses() == null ? null : Math.max(0, invite.getMaxUses() - invite.getUsesCount());
        }
        return "PENDING".equalsIgnoreCase(invite.getStatus()) ? 1 : 0;
    }

    private void decorateTeamView(Invite view, Invite invite, User caller, boolean privileged) {
        view.setMultiUse(invite.isMultiUse());
        view.setRemainingUses(remainingUses(invite));
        if (!invite.isMultiUse()) return;
        view.setStatus(teamStatus(invite, LocalDateTime.now()));
        if (privileged) {
            view.setMaxUses(invite.getMaxUses());
            view.setUsesCount(invite.getUsesCount());
        }
        view.setAlreadyMember(caller != null
                && (redemptionRepository.existsByInviteIdAndUserId(invite.getId(), caller.getId()) || isLinkedToSquad(invite.getSquadId(), caller)));
    }

    /**
     * A sessão resolve a squad ativa por project_member_roles (UserProjectResolverService), não por
     * squad_members: sem esta linha o convidado voltava pro onboarding e sumia do roster. Entra sem
     * liderança (isLeadership=false), mesma regra do /projects/{key}/join — governança vem do Jira ou de admin.
     */
    private void linkProjectMembership(Invite invite, User user) {
        String projectId = invite.getSquadId();
        if (!projectConfigRepository.existsById(projectId)) {
            return;
        }
        boolean alreadyLinked = projectMemberRoleRepository.findByProjectId(projectId).stream()
                .anyMatch(m -> user.getId().equals(m.getUserId()));
        if (!alreadyLinked) {
            String roleName = invite.getRoleName();
            projectMemberRoleRepository.save(ProjectMemberRole.builder()
                    .projectId(projectId)
                    .roleName(roleName)
                    .roleKey(roleName.toUpperCase().replace(" ", "_").replaceAll("[^A-Z_]", ""))
                    .jiraAccountId(user.getJiraAccountId())
                    .displayName(user.getName())
                    .email(user.getEmail())
                    .userId(user.getId())
                    .isLeadership(false)
                    .source(com.agilespace.backend.domain.MembershipSource.INVITE)
                    .build());
        }
        if (user.getDefaultProjectId() == null || user.getDefaultProjectId().isBlank()) {
            user.setDefaultProjectId(projectId);
        }
        userRepository.save(user);
    }

    private String generateToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void audit(String action, String performedBy, String details) {
        AuditLog entry = AuditLog.builder()
                .id(UUID.randomUUID().toString())
                .action(action)
                .performedBy(performedBy != null ? performedBy : "SYSTEM")
                .details(details)
                .createdAt(LocalDateTime.now())
                .build();
        auditLogRepository.save(entry);
    }
}
