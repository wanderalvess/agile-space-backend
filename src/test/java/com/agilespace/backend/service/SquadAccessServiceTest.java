package com.agilespace.backend.service;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.UserProjectAccessDto;
import com.agilespace.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SquadAccessService - pertencimento e gestão da squad")
class SquadAccessServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private SquadService squadService;
    @Mock private UserProjectResolverService resolver;

    private SquadAccessService service;

    @BeforeEach
    void setUp() {
        service = new SquadAccessService(userRepository, squadService, resolver);
    }

    private User user(String id, String squadId) {
        User u = User.builder().id(id).email(id + "@empresa.com.br").squadId(squadId).build();
        lenient().when(userRepository.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    private UserProjectAccessDto.ProjectAccessItem project(String id, boolean leadership) {
        return UserProjectAccessDto.ProjectAccessItem.builder().projectId(id).isLeadership(leadership).build();
    }

    @Test
    @DisplayName("Liderança transversal de uma tribo NÃO lê squad de outra tribo")
    void transversalLeaderOnlyReachesProjectsOfOwnTribe() {
        user("pl1", null);
        when(resolver.resolveUserAccess(any())).thenReturn(UserProjectAccessDto.builder()
                .isTransversalLeader(true)
                .projects(List.of(project("TRIBO-A-1", true), project("TRIBO-A-2", true)))
                .build());
        when(squadService.getMembers(any())).thenReturn(List.of());

        assertTrue(service.matchesSquad("TRIBO-A-2", "pl1", "MEMBER"));
        assertFalse(service.matchesSquad("TRIBO-B-1", "pl1", "MEMBER"));
    }

    @Test
    @DisplayName("Dev da squad lê, mas não gerencia quando a squad já tem liderança cadastrada")
    void developerReadsButCannotManageWhenLeadershipRegistered() {
        user("dev1", "SQ1");
        when(resolver.resolveUserAccess(any())).thenReturn(UserProjectAccessDto.builder()
                .projects(List.of(project("SQ1", false))).build());
        when(resolver.hasRegisteredLeadership("SQ1")).thenReturn(true);

        assertTrue(service.matchesSquad("SQ1", "dev1", "MEMBER"));
        assertFalse(service.canManageSquad("SQ1", "dev1", "MEMBER"));
    }

    @Test
    @DisplayName("Quem tem papel de liderança na squad gerencia")
    void leaderOfTheSquadManages() {
        user("am1", "SQ1");
        when(resolver.resolveUserAccess(any())).thenReturn(UserProjectAccessDto.builder()
                .projects(List.of(project("SQ1", true))).build());

        assertTrue(service.canManageSquad("SQ1", "am1", "MEMBER"));
    }

    @Test
    @DisplayName("Liderança de OUTRA squad não gerencia esta")
    void leaderOfAnotherSquadCannotManage() {
        user("am2", "SQ2");
        when(resolver.resolveUserAccess(any())).thenReturn(UserProjectAccessDto.builder()
                .projects(List.of(project("SQ2", true))).build());
        when(squadService.getMembers(any())).thenReturn(List.of());

        assertFalse(service.canManageSquad("SQ1", "am2", "MEMBER"));
    }

    @Test
    @DisplayName("Squad sem nenhuma liderança cadastrada mantém a gestão aberta aos membros (para não travar a configuração)")
    void squadWithoutRegisteredLeadershipKeepsLegacyRule() {
        user("dev1", "SQ1");
        when(resolver.resolveUserAccess(any())).thenReturn(UserProjectAccessDto.builder().projects(List.of()).build());
        when(resolver.hasRegisteredLeadership("SQ1")).thenReturn(false);

        assertTrue(service.canManageSquad("SQ1", "dev1", "MEMBER"));
    }

    @Test
    @DisplayName("Admin gerencia qualquer squad sem consultar o banco")
    void adminManagesAnySquad() {
        assertTrue(service.canManageSquad("SQ1", "qualquer", "ADMIN"));
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("Cargo de liderança só vale dentro da própria squad")
    void leadershipJobTitleOnlyInsideOwnSquad() {
        User outsider = user("tl9", "SQ9");
        outsider.setJobTitle("tech lead");
        when(resolver.resolveUserAccess(any())).thenReturn(UserProjectAccessDto.builder().projects(List.of()).build());
        when(squadService.getMembers(any())).thenReturn(List.of());

        assertFalse(service.canManageSquad("SQ1", "tl9", "MEMBER"));
    }
}
