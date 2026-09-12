package com.agilespace.backend.service;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.domain.Invite;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.repository.InviteRepository;
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

    private final InviteRepository inviteRepository;
    private final AuditLogRepository auditLogRepository;
    private final SquadService squadService;

    @Transactional
    public Invite createInvite(String squadId, String roleName, String email, String invitedByUserId) {
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

    @Transactional
    public void revoke(String id, String squadId, String revokedBy) {
        Invite invite = inviteRepository.findById(id)
                .filter(i -> i.getSquadId().equalsIgnoreCase(squadId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Convite não encontrado"));
        invite.setStatus("REVOKED");
        inviteRepository.save(invite);
        audit("INVITE_REVOKED", revokedBy, "Convite " + id + " revogado");
    }

    @Transactional
    public SquadMember acceptInvite(String token, User acceptingUser) {
        Invite invite = inviteRepository.findByToken(token)
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

        invite.setStatus("ACCEPTED");
        invite.setAcceptedAt(LocalDateTime.now());
        invite.setAcceptedByUserId(acceptingUser.getId());
        inviteRepository.save(invite);

        audit("INVITE_ACCEPTED", acceptingUser.getEmail(),
                "Convite " + invite.getId() + " aceito, vinculado ao squad " + invite.getSquadId() + " como " + invite.getRoleName());
        log.info("Convite {} aceito por {} (squad {})", invite.getId(), acceptingUser.getEmail(), invite.getSquadId());
        return saved;
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
