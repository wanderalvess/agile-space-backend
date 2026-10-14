package com.agilespace.backend.service;

import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SquadAccessService - o nome da conta não dá acesso à squad (homônimo)")
class SquadAccessServiceNameMatchTest {

    private final UserRepository users = mock(UserRepository.class);
    private final SquadService squads = mock(SquadService.class);
    private final SquadAccessService service = new SquadAccessService(users, squads, null);

    private SquadMember rosterEntry() {
        return SquadMember.builder().squadId("TIMEA").jiraAccountId("ana.n").displayName("Ana Paula Nogueira")
                .email("ana.paula@totvs.com.br").build();
    }

    private User user(String id, String name, String email, String jiraId) {
        User u = User.builder().id(id).name(name).email(email).jiraAccountId(jiraId).role("MEMBER").build();
        when(users.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    @DisplayName("Homônimo (mesmo nome, outro e-mail e outra conta) NÃO entra na squad")
    void namesakeIsRejected() {
        user("u-homonimo", "Ana Paula Nogueira", "outra.ana@totvs.com.br", "outra.ana");
        when(squads.getMembers("TIMEA")).thenReturn(List.of(rosterEntry()));

        assertFalse(service.matchesSquad("TIMEA", "u-homonimo", "MEMBER"));
    }

    @Test
    @DisplayName("Quem tem o mesmo e-mail do roster entra")
    void sameEmailMatches() {
        user("u-ana", "Ana", "ANA.PAULA@totvs.com.br", null);
        when(squads.getMembers("TIMEA")).thenReturn(List.of(rosterEntry()));

        assertTrue(service.matchesSquad("TIMEA", "u-ana", "MEMBER"));
    }

    @Test
    @DisplayName("Quem tem a mesma conta do Jira entra")
    void sameJiraAccountMatches() {
        user("u-ana2", "Qualquer Nome", "ana@outro.com.br", "ANA.N");
        when(squads.getMembers("TIMEA")).thenReturn(List.of(rosterEntry()));

        assertTrue(service.matchesSquad("TIMEA", "u-ana2", "MEMBER"));
    }

    @Test
    @DisplayName("Quem reivindicou a vaga (\"Sou eu\") entra, mesmo com nome e e-mail diferentes")
    void claimedSeatMatches() {
        user("u-claim", "Apelido", "pessoal@totvs.com.br", null);
        SquadMember claimed = rosterEntry();
        claimed.setClaimedByUid("u-claim");
        when(squads.getMembers("TIMEA")).thenReturn(List.of(claimed));

        assertTrue(service.matchesSquad("TIMEA", "u-claim", "MEMBER"));
    }
}
