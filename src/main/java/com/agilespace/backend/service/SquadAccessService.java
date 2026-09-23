package com.agilespace.backend.service;

import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.UserProjectAccessDto;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.security.SquadLeadership;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * Controle de acesso "esse usuário pertence a esse squad?" — extraído de SquadController
 * (única fonte de checagem antes) pra ser reaproveitado por qualquer endpoint que lista
 * dado por squad (poker, showcase, retro, health-check, brainstorming), que antes não
 * tinham checagem nenhuma além de autenticação genérica.
 */
@Service
@RequiredArgsConstructor
public class SquadAccessService {

    private final UserRepository userRepository;
    private final SquadService squadService;
    private final UserProjectResolverService userProjectResolverService;

    /**
     * Núcleo comum de leitura E escrita: ADMIN/LEAD, ou um caller já vinculado a esta squad
     * (User.squadId/defaultProjectId, papel em projeto resolvido, ou membro no roster
     * squad_members). Não tem efeito colateral nenhum — nunca grava nada.
     */
    public boolean matchesSquad(String squadId, HttpServletRequest request) {
        String role = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
        if ("ADMIN".equalsIgnoreCase(role) || "LEAD".equalsIgnoreCase(role)) {
            return true;
        }
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        User caller = userId != null ? userRepository.findById(userId).orElse(null) : null;
        if (caller == null) {
            return false;
        }

        // 0. Papel administrativo no registro do banco (caso o token JWT não esteja atualizado)
        if ("ADMIN".equalsIgnoreCase(caller.getRole()) || "LEAD".equalsIgnoreCase(caller.getRole())) {
            return true;
        }

        // 1. Checagem direta por squadId ou defaultProjectId
        boolean matches = (caller.getSquadId() != null && caller.getSquadId().equalsIgnoreCase(squadId))
                || (caller.getDefaultProjectId() != null && caller.getDefaultProjectId().equalsIgnoreCase(squadId));

        // 2. Tratamento de alias DDWMISSI <-> MISSI
        if (!matches && ("DDWMISSI".equalsIgnoreCase(squadId) || "MISSI".equalsIgnoreCase(squadId))) {
            matches = ("DDWMISSI".equalsIgnoreCase(caller.getSquadId()) || "MISSI".equalsIgnoreCase(caller.getSquadId()))
                    || ("DDWMISSI".equalsIgnoreCase(caller.getDefaultProjectId()) || "MISSI".equalsIgnoreCase(caller.getDefaultProjectId()));
        }

        // 0.1 Cargos de liderança/governança de squad — só vale se o caller já pertence a este squad
        // (senão qualquer usuário autodeclarando jobTitle de liderança ganharia acesso a squads alheios)
        if (matches && SquadLeadership.isLeadershipJobTitle(caller.getJobTitle())) {
            return true;
        }
        if (matches) {
            return true;
        }

        // 3. Checagem através dos projetos resolvidos pelo UserProjectResolverService
        if (userProjectResolverService != null) {
            UserProjectAccessDto access = userProjectResolverService.resolveUserAccess(caller);
            if (access != null) {
                if (access.isTransversalLeader()) {
                    return true;
                }
                if (access.getProjects() != null) {
                    matches = access.getProjects().stream().anyMatch(p ->
                            p.getProjectId().equalsIgnoreCase(squadId)
                            || (("DDWMISSI".equalsIgnoreCase(squadId) || "MISSI".equalsIgnoreCase(squadId))
                                && ("DDWMISSI".equalsIgnoreCase(p.getProjectId()) || "MISSI".equalsIgnoreCase(p.getProjectId())))
                    );
                    if (matches) return true;
                }
            }
        }

        // 4. Checagem se o usuário é membro registrado desta squad na tabela squad_members
        List<SquadMember> members = squadService.getMembers(squadId);
        if ("DDWMISSI".equalsIgnoreCase(squadId) || "MISSI".equalsIgnoreCase(squadId)) {
            String aliasSquad = "DDWMISSI".equalsIgnoreCase(squadId) ? "MISSI" : "DDWMISSI";
            List<SquadMember> aliasMembers = squadService.getMembers(aliasSquad);
            if (aliasMembers != null && !aliasMembers.isEmpty()) {
                List<SquadMember> combined = new ArrayList<>(members != null ? members : List.of());
                combined.addAll(aliasMembers);
                members = combined;
            }
        }
        if (members != null && !members.isEmpty()) {
            matches = members.stream().anyMatch(m ->
                    (m.getClaimedByUid() != null && m.getClaimedByUid().equals(caller.getId()))
                    || (caller.getEmail() != null && m.getEmail() != null && caller.getEmail().trim().equalsIgnoreCase(m.getEmail().trim()))
                    || (caller.getJiraAccountId() != null && m.getJiraAccountId() != null && caller.getJiraAccountId().trim().equalsIgnoreCase(m.getJiraAccountId().trim()))
                    || (caller.getName() != null && m.getDisplayName() != null && caller.getName().trim().equalsIgnoreCase(m.getDisplayName().trim()))
            );
        }
        return matches;
    }

    /**
     * Leitura: qualquer membro real da squad (ou admin/liderança) — nunca auto-vincula.
     */
    public void requireSquadReadAccess(String squadId, HttpServletRequest request) {
        if (!matchesSquad(squadId, request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a membros desta squad ou administradores.");
        }
    }
}
