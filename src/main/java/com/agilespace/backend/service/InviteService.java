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

    private final InviteRepository inviteRepository;
    private final AuditLogRepository auditLogRepository;
    private final SquadService squadService;
    private final ProjectConfigRepository projectConfigRepository;
    private final ProjectMemberRoleRepository projectMemberRoleRepository;
    private final UserRepository userRepository;

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
        String status = invite.getStatus();
        if ("PENDING".equalsIgnoreCase(status) && invite.getExpiresAt().isBefore(LocalDateTime.now())) {
            status = "EXPIRED";
        }
        java.util.Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("squadId", invite.getSquadId());
        view.put("squadName", squadName);
        view.put("roleName", invite.getRoleName());
        view.put("status", status);
        view.put("expiresAt", invite.getExpiresAt());
        view.put("emailMasked", maskEmail(invite.getEmail()));
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
        inviteRepository.save(invite);
        audit("INVITE_REVOKED", revokedBy, "Convite " + id + " revogado");
    }

    @Transactional
    public SquadMember acceptInvite(String token, User acceptingUser) {
        Invite invite = inviteRepository.findByTokenForUpdate(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Convite não encontrado"));

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
