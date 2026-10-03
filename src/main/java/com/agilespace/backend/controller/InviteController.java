package com.agilespace.backend.controller;

import com.agilespace.backend.domain.Invite;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.security.SquadLeadership;
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
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class InviteController {

    private final InviteService inviteService;
    private final UserRepository userRepository;
    private final ProjectMemberRoleRepository projectMemberRoleRepository;

    /**
     * ADMIN/LEAD (tier de sistema) entram em qualquer squad; liderança de negócio só
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

        if ("ADMIN".equalsIgnoreCase(role) || "LEAD".equalsIgnoreCase(role)) {
            return caller;
        }
        if (caller == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a administradores ou lideranças do squad.");
        }
        if ("ADMIN".equalsIgnoreCase(caller.getRole()) || "LEAD".equalsIgnoreCase(caller.getRole())) {
            return caller;
        }

        boolean ownsSquad = (caller.getSquadId() != null && caller.getSquadId().equalsIgnoreCase(squadId))
                || (caller.getDefaultProjectId() != null && caller.getDefaultProjectId().equalsIgnoreCase(squadId));
        if (!ownsSquad) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a administradores ou lideranças deste squad.");
        }

        if (SquadLeadership.isLeadershipJobTitle(caller.getJobTitle())) {
            return caller;
        }

        boolean isProjectLeadership = projectMemberRoleRepository.findByProjectId(squadId).stream()
                .anyMatch(pmr -> pmr.isLeadership()
                        && ((pmr.getUserId() != null && pmr.getUserId().equals(caller.getId()))
                            || (pmr.getEmail() != null && caller.getEmail() != null && pmr.getEmail().equalsIgnoreCase(caller.getEmail()))));
        if (isProjectLeadership) {
            return caller;
        }

        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a administradores ou lideranças deste squad.");
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
    public ResponseEntity<Invite> getInvite(@PathVariable String token) {
        return ResponseEntity.ok(inviteService.getByToken(token));
    }

    @PostMapping("/api/invites/{token}/accept")
    public ResponseEntity<SquadMember> acceptInvite(@PathVariable String token, HttpServletRequest request) {
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        User acceptingUser = userId != null ? userRepository.findById(userId).orElse(null) : null;
        if (acceptingUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida");
        }
        return ResponseEntity.ok(inviteService.acceptInvite(token, acceptingUser));
    }
}
