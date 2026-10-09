package com.agilespace.backend.service;

import com.agilespace.backend.domain.SquadMemberExclusion;
import com.agilespace.backend.repository.SquadMemberExclusionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Quem a liderança removeu à mão do time de uma squad. Todo caminho automático que coloca gente no time
 * (sync do Jira, importação de quadro, reimportação Profields, "join" por conta própria) consulta isto.
 * Adicionar a pessoa de novo à mão (ou por convite) limpa a exclusão.
 */
@Component
@RequiredArgsConstructor
public class SquadMemberExclusions {

    private final SquadMemberExclusionRepository repository;

    public record Removed(Set<String> accountIds, Set<String> emails) {
        public static final Removed NONE = new Removed(Set.of(), Set.of());

        public boolean matches(String jiraAccountId, String email) {
            if (jiraAccountId != null && !jiraAccountId.isBlank() && accountIds.contains(jiraAccountId.trim())) return true;
            return email != null && !email.isBlank() && emails.contains(email.trim().toLowerCase(Locale.ROOT));
        }

        public boolean isEmpty() {
            return accountIds.isEmpty() && emails.isEmpty();
        }
    }

    /** Null-safe para quem recebe este bean por injeção opcional. */
    public static Removed of(SquadMemberExclusions exclusions, String squadId) {
        return exclusions == null ? Removed.NONE : exclusions.forSquad(squadId);
    }

    public Removed forSquad(String squadId) {
        if (repository == null || squadId == null) return Removed.NONE;
        Set<String> ids = new HashSet<>();
        Set<String> emails = new HashSet<>();
        for (SquadMemberExclusion e : repository.findBySquadId(squadId)) {
            if (e.getJiraAccountId() != null) ids.add(e.getJiraAccountId());
            if (e.getEmail() != null && !e.getEmail().isBlank()) emails.add(e.getEmail().trim().toLowerCase(Locale.ROOT));
        }
        return new Removed(ids, emails);
    }
}
