package com.agilespace.backend.security;

import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.SquadMemberRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * O id de conta do Jira é uma chave de acesso: UserProjectResolverService e SquadAccessService ligam a
 * conta às linhas do roster (e portanto a equipes e cargos de liderança) por esse valor. Se qualquer
 * pessoa pudesse digitar o id de outra no próprio perfil, herdaria o acesso e a liderança dela.
 *
 * Por isso um id só pode ser gravado por quem não é admin quando nenhuma linha do roster que o usa
 * pertence a outra pessoa (outra conta vinculada ou outro e-mail). Quem precisa se ligar a uma linha
 * existente usa "Sou eu" no onboarding, que grava o id pelo servidor.
 */
@Component
public class JiraAccountIdGuard {

    private final ProjectMemberRoleRepository projectMemberRoleRepository;
    private final SquadMemberRepository squadMemberRepository;

    public JiraAccountIdGuard(ProjectMemberRoleRepository projectMemberRoleRepository,
                              SquadMemberRepository squadMemberRepository) {
        this.projectMemberRoleRepository = projectMemberRoleRepository;
        this.squadMemberRepository = squadMemberRepository;
    }

    /** true se {@code jiraAccountId} não aponta para o roster de outra pessoa. Vazio sempre é livre. */
    public boolean isFreeFor(String jiraAccountId, String userId, String email) {
        if (jiraAccountId == null || jiraAccountId.isBlank()) {
            return true;
        }
        String id = jiraAccountId.trim();

        List<ProjectMemberRole> roles = projectMemberRoleRepository.findByJiraAccountId(id);
        if (roles != null) {
            for (ProjectMemberRole r : roles) {
                boolean mine = (r.getUserId() != null && r.getUserId().equals(userId))
                        || sameEmail(r.getEmail(), email);
                if (!mine) {
                    return false;
                }
            }
        }

        List<SquadMember> members = squadMemberRepository.findByUserIdentifier(id);
        if (members != null) {
            for (SquadMember m : members) {
                boolean mine = (m.getClaimedByUid() != null && m.getClaimedByUid().equals(userId))
                        || sameEmail(m.getEmail(), email);
                if (!mine) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean sameEmail(String a, String b) {
        return a != null && b != null && !a.isBlank() && a.trim().equalsIgnoreCase(b.trim());
    }
}
