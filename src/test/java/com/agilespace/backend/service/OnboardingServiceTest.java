package com.agilespace.backend.service;

import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.SquadMemberRepository;
import com.agilespace.backend.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OnboardingService - 'sou eu' exige comprovação de identidade")
class OnboardingServiceTest {

    @Mock private ProjectMemberRoleRepository projectMemberRoleRepository;
    @Mock private ProjectConfigRepository projectConfigRepository;
    @Mock private SquadMemberRepository squadMemberRepository;
    @Mock private UserRepository userRepository;
    @InjectMocks private OnboardingService service;

    private User user() {
        return User.builder().id("u1").name("Ana Paula Nogueira").email("ana@totvs.com.br").build();
    }

    private ProjectMemberRole row(String id, String email, String account, String name) {
        return ProjectMemberRole.builder().id(id).projectId("P1").roleName("Developer").roleKey("DEVELOPER")
                .email(email).jiraAccountId(account).displayName(name).isLeadership(false).build();
    }

    private void stubClaim(ProjectMemberRole r, List<ProjectMemberRole> all) {
        when(projectMemberRoleRepository.findById(r.getId())).thenReturn(Optional.of(r));
        lenient().when(projectMemberRoleRepository.findByProjectId("P1")).thenReturn(all);
    }

    @Test
    @DisplayName("Claim com e-mail igual (sem caixa) é aceito e grava o vínculo")
    void claimByMatchingEmail() {
        ProjectMemberRole r = row("r1", "ANA@totvs.com.br", "acc1", "Outro Nome");
        stubClaim(r, List.of(r));
        assertEquals("P1", service.claim("r1", user()));
        assertEquals("u1", r.getUserId());
        verify(projectMemberRoleRepository).save(r);
    }

    @Test
    @DisplayName("Claim por conta do Jira igual é aceito")
    void claimByJiraAccount() {
        ProjectMemberRole r = row("r1", "outro@x.com", "acc1", "Fulano");
        User u = user();
        u.setJiraAccountId("acc1");
        stubClaim(r, List.of(r));
        assertEquals("P1", service.claim("r1", u));
    }

    @Test
    @DisplayName("Linha com e-mail de outra pessoa é recusada (403), mesmo com nome igual")
    void claimRejectsDifferentEmail() {
        ProjectMemberRole r = row("r1", "outra@totvs.com.br", null, "Ana Paula Nogueira");
        stubClaim(r, List.of(r));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.claim("r1", user()));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertNull(r.getUserId());
        verify(projectMemberRoleRepository, never()).save(any());
    }

    @Test
    @DisplayName("Linha só com nome: sem estar aprovado no projeto é recusada")
    void claimNameOnlyNeedsApproval() {
        ProjectMemberRole r = row("r1", null, null, "Ana Paula Nogueira");
        stubClaim(r, List.of(r));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.claim("r1", user()));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(projectMemberRoleRepository, never()).save(any());
    }

    @Test
    @DisplayName("Linha só com nome: nome completo igual + já aprovado (vínculo/convite) no projeto é aceita")
    void claimNameOnlyWhenApprovedInProject() {
        ProjectMemberRole r = row("r1", null, null, "ana  PAULA nogueira");
        ProjectMemberRole mine = row("r0", "ana@totvs.com.br", null, "Ana");
        mine.setUserId("u1");
        stubClaim(r, List.of(r, mine));
        assertEquals("P1", service.claim("r1", user()));
        assertEquals("u1", r.getUserId());
    }

    @Test
    @DisplayName("Aprovado no projeto mas só primeiro+último nome parecidos: recusa (exige nome completo)")
    void claimNameOnlyRequiresFullName() {
        ProjectMemberRole r = row("r1", null, null, "Ana Nogueira");
        ProjectMemberRole mine = row("r0", "ana@totvs.com.br", null, "Ana");
        mine.setUserId("u1");
        stubClaim(r, List.of(r, mine));
        assertThrows(ResponseStatusException.class, () -> service.claim("r1", user()));
    }

    @Test
    @DisplayName("Linha de outra conta continua 409 e liderança continua 403")
    void claimStillRejectsTakenAndLeadership() {
        ProjectMemberRole taken = row("r1", "ana@totvs.com.br", null, "Ana");
        taken.setUserId("outro");
        stubClaim(taken, List.of(taken));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> service.claim("r1", user())).getStatusCode());

        ProjectMemberRole lead = row("r2", "ana@totvs.com.br", null, "Ana");
        lead.setLeadership(true);
        when(projectMemberRoleRepository.findById("r2")).thenReturn(Optional.of(lead));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> service.claim("r2", user())).getStatusCode());
    }

    @Test
    @DisplayName("Sugestão de 1 clique só aparece quando o claim seria aceito")
    void suggestionsOnlyClaimable() {
        ProjectMemberRole ok = row("r1", "ana@totvs.com.br", null, "Ana Paula Nogueira");
        ProjectMemberRole nope = row("r2", "outra@x.com", null, "Ana Paula Nogueira");
        nope.setProjectId("P2");
        when(projectMemberRoleRepository.findAll()).thenReturn(List.of(ok, nope));
        lenient().when(projectMemberRoleRepository.findByProjectId("P1")).thenReturn(List.of(ok));
        lenient().when(projectMemberRoleRepository.findByProjectId("P2")).thenReturn(List.of(nope));
        lenient().when(projectConfigRepository.findById(any())).thenReturn(Optional.empty());

        var result = service.suggestionsFor(user());
        assertEquals(1, result.size());
        assertEquals("P1", result.get(0).getProjectId());
        assertTrue(result.get(0).getMembers().get(0).isClaimable());
    }

    @Test
    @DisplayName("linkApproved (admin): vincula sem checar identidade, aceita liderança e herda o accountId")
    void linkApprovedSkipsIdentityAndAllowsLeadership() {
        ProjectMemberRole lead = row("r1", "outra.pessoa@totvs.com.br", "acc-lead", "Fulana");
        lead.setLeadership(true);
        User u = user();

        service.linkApproved(lead, u);

        assertEquals("u1", lead.getUserId());
        assertEquals("acc-lead", u.getJiraAccountId());
        verify(projectMemberRoleRepository).save(lead);
        verify(userRepository).save(u);

        // o self-service continua barrando a mesma linha
        lead.setUserId(null);
        stubClaim(lead, List.of(lead));
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> service.claim("r1", user())).getStatusCode());
    }

    @Test
    @DisplayName("linkApproved: linha de outra conta é 409; a mesma conta é idempotente")
    void linkApprovedConflictAndIdempotent() {
        ProjectMemberRole taken = row("r1", "x@y.com", null, "Fulano");
        taken.setUserId("outro");
        assertEquals(HttpStatus.CONFLICT,
                assertThrows(ResponseStatusException.class, () -> service.linkApproved(taken, user())).getStatusCode());
        verify(projectMemberRoleRepository, never()).save(any());

        ProjectMemberRole mine = row("r2", "x@y.com", null, "Fulano");
        mine.setUserId("u1");
        assertDoesNotThrow(() -> service.linkApproved(mine, user()));
        assertEquals("u1", mine.getUserId());
    }
}
