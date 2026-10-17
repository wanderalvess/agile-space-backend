package com.agilespace.backend.controller;

import com.agilespace.backend.domain.Invite;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.AuthResponseDto;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.security.LeadPolicy;
import com.agilespace.backend.security.SquadLeadership;
import com.agilespace.backend.service.AuthService;
import com.agilespace.backend.service.AuthzShadow;
import com.agilespace.backend.service.SquadAccessService;
import com.agilespace.backend.service.InviteService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Convite real (token/link) pra vincular alguém a um squad + papel de negócio.
 * Fica fora de /api/admin de propósito: quem cria/gerencia convite não precisa
 * ser ADMIN de sistema, só liderança do próprio squad (ver requireInviteAccess).
 */
@RestController
@RequiredArgsConstructor
public class InviteController {

    private final InviteService inviteService;
    private final UserRepository userRepository;
    private final ProjectMemberRoleRepository projectMemberRoleRepository;
    private final AuthService authService;
    private final SquadAccessService squadAccessService;
    private final AuthzShadow authzShadow;

    /** Etapa 4 (app.membership.lead-level). Montagem manual: legacy (LEAD concede, como antes). */
    private LeadPolicy leadPolicy = LeadPolicy.LEGACY;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLeadPolicy(LeadPolicy leadPolicy) {
        if (leadPolicy != null) this.leadPolicy = leadPolicy;
    }

    /**
     * ADMIN (tier de sistema; LEAD só com app.membership.lead-level=legacy) entra em qualquer squad; liderança de negócio só
     * entra no squad ao qual já está vinculada — ao contrário do SquadController.
     * requireSquadWriteAccess legado, não aceita liderança pra qualquer squadId.
     *
     * Liderança é checada de duas formas, porque nenhuma sozinha cobre todo mundo:
     * ProjectMemberRole.isLeadership (fonte oficial, vem do Jira Profields — é o que
     * "Criar Novo Projeto"/"Importar do Jira" realmente grava pro criador/Agile Master)
     * e User.jobTitle (autodeclarado, usado quando não há sync de Jira nesse squad).
     */
    private User requireInviteAccess(String squadId, HttpServletRequest request) {
        String role = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        User caller = userId != null ? userRepository.findById(userId).orElse(null) : null;

        if (leadPolicy.isPrivileged(role)) {
            return caller;
        }
        if (caller == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a administradores ou lideranças do squad.");
        }
        if (leadPolicy.isPrivileged(caller.getRole())) {
            return caller;
        }

        boolean allowed = authzShadow.decide("invite", caller.getId(), squadId,
                () -> legacyInviteAllowed(squadId, caller),
                () -> squadAccessService.hasDirectLeadership(caller, squadId),
                legacyAllowed -> legacyAllowed
                        ? (SquadLeadership.isLeadershipJobTitle(caller.getJobTitle()) ? "ONLY_JOB_TITLE" : "RESOLVER_ONLY")
                        : "NEW_ONLY_MEMBERSHIP");
        if (allowed) {
            return caller;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a administradores ou lideranças deste squad.");
    }

    /**
     * Regra LEGADA (app.membership.authz=legacy): quem tem a squad em users.squad_id/default_project_id e cargo de
     * liderança, ou liderança em linha própria de project_member_roles (por conta ou e-mail), mesmo com outra squad
     * ativa. A regra nova (new) só vale a liderança em linha PRÓPRIA do vínculo; o cargo e users.squad_id deixam de valer.
     */
    private boolean legacyInviteAllowed(String squadId, User caller) {
        boolean ownsSquad = (caller.getSquadId() != null && caller.getSquadId().equalsIgnoreCase(squadId))
                || (caller.getDefaultProjectId() != null && caller.getDefaultProjectId().equalsIgnoreCase(squadId));
        if (!ownsSquad) {
            // Liderança de roster conta mesmo com outra squad ativa (ex.: AM que acabou de criar uma equipe manual).
            return isRosterLeader(squadId, caller);
        }
        if (SquadLeadership.isLeadershipJobTitle(caller.getJobTitle())) {
            return true;
        }
        return isRosterLeader(squadId, caller);
    }

    private boolean isRosterLeader(String squadId, User caller) {
        return projectMemberRoleRepository.findByProjectId(squadId).stream()
                .anyMatch(pmr -> pmr.isLeadership()
                        && ((pmr.getUserId() != null && pmr.getUserId().equals(caller.getId()))
                            || (pmr.getEmail() != null && caller.getEmail() != null && pmr.getEmail().equalsIgnoreCase(caller.getEmail()))));
    }

    @PostMapping("/api/squads/{squadId}/invites")
    public ResponseEntity<Invite> createInvite(
            @PathVariable String squadId,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        User caller = requireInviteAccess(squadId, request);
        String roleName = body.get("roleName");
        if (roleName == null || roleName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roleName é obrigatório");
        }
        String invitedBy = caller != null ? caller.getEmail() : (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_EMAIL);
        Invite invite = inviteService.createInvite(squadId, roleName, body.get("email"), invitedBy);
        return ResponseEntity.status(HttpStatus.CREATED).body(invite);
    }

    @GetMapping("/api/squads/{squadId}/invites")
    public ResponseEntity<List<Invite>> listInvites(@PathVariable String squadId, HttpServletRequest request) {
        requireInviteAccess(squadId, request);
        return ResponseEntity.ok(inviteService.listPending(squadId));
    }

    @DeleteMapping("/api/squads/{squadId}/invites/{id}")
    public ResponseEntity<Void> revokeInvite(
            @PathVariable String squadId,
            @PathVariable String id,
            HttpServletRequest request) {
        User caller = requireInviteAccess(squadId, request);
        String revokedBy = caller != null ? caller.getEmail() : (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_EMAIL);
        inviteService.revoke(id, squadId, revokedBy);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/invites/{token}")
    public ResponseEntity<Invite> getInvite(@PathVariable String token, HttpServletRequest request) {
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        User caller = userId != null ? userRepository.findById(userId).orElse(null) : null;
        return ResponseEntity.ok(inviteService.getForDisplay(token, caller));
    }

    @PostMapping("/api/invites/{token}/accept")
    public ResponseEntity<AuthResponseDto> acceptInvite(@PathVariable String token, HttpServletRequest request) {
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        User acceptingUser = userId != null ? userRepository.findById(userId).orElse(null) : null;
        if (acceptingUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida");
        }
        SquadMember member = inviteService.acceptInvite(token, acceptingUser);
        // Devolve a sessão já com a squad ativa (mesmo contrato de /projects/{key}/join), pro cliente não precisar
        // de um segundo round-trip nem cair de volta no onboarding.
        try {
            return ResponseEntity.ok(authService.switchActiveProject(acceptingUser.getId(), member.getSquadId()));
        } catch (ResponseStatusException e) {
            // Squad sem projeto cadastrado (só roster): não há projeto ativo pra trocar.
            return ResponseEntity.ok(authService.getMe(acceptingUser.getId()));
        }
    }
}
