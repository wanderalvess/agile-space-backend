package com.agilespace.backend.service;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** A chave app.membership.bootstrap: só "on" liga; vazio, "off" ou desconhecido ficam desligados; legacy/shadow ignoram a chave. */
class SquadBootstrapServiceFlagTest {

    private final UserRepository users = mock(UserRepository.class);
    private final SquadService squads = mock(SquadService.class);
    private final ProjectMemberRoleRepository pmr = mock(ProjectMemberRoleRepository.class);
    private final ProjectConfigRepository projects = mock(ProjectConfigRepository.class);
    private final JiraProfieldsService profields = mock(JiraProfieldsService.class);
    private final MembershipService membership = mock(MembershipService.class);

    @SuppressWarnings("unchecked")
    private SquadBootstrapService service(String authzMode, String flag) {
        AuthzShadow authz = new AuthzShadow(authzMode, 60, mock(ObjectProvider.class));
        return new SquadBootstrapService(users, squads, pmr, projects, profields, membership, authz, flag);
    }

    private void callerWithoutSquad() {
        when(users.findById("u1")).thenReturn(Optional.of(User.builder().id("u1").email("u1@x.com").name("U").build()));
        when(squads.getSquad("NOVA")).thenReturn(Optional.empty());
        when(squads.getMembers("NOVA")).thenReturn(List.of());
        when(pmr.findByProjectIdsUpper(any())).thenReturn(List.of());
        when(membership.membershipsOf(any())).thenReturn(List.of());
    }

    @Test
    void soOnLigaOVinculoEmNew() {
        assertTrue(service("new", "on").createsMembership());
        assertTrue(service("new", " ON ").createsMembership());
        for (String flag : new String[] {null, "", "off", "ligado", "true", "1"}) {
            assertFalse(service("new", flag).createsMembership(), "flag=" + flag);
        }
    }

    @Test
    void newComFlagDesligada_naoConcedeNemGrava() {
        callerWithoutSquad();
        for (String flag : new String[] {null, "off", "ligado"}) {
            assertFalse(service("new", flag).bootstrap("NOVA", "u1"), "flag=" + flag);
        }
        verify(users, never()).save(any());
        verify(pmr, never()).save(any());
        verify(projects, never()).save(any());
    }

    @Test
    void legacyEShadow_ignoramAChave_eSoGravamOSquadId() {
        for (String mode : new String[] {"legacy", "shadow"}) {
            for (String flag : new String[] {"off", "on"}) {
                reset(users, squads, pmr, projects);
                callerWithoutSquad();
                assertTrue(service(mode, flag).bootstrap("NOVA", "u1"), mode + "/" + flag);
                verify(users).save(any());
                verify(pmr, never()).save(any());
                verify(projects, never()).save(any());
            }
        }
    }
}
