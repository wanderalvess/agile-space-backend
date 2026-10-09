package com.agilespace.backend.service;

import com.agilespace.backend.domain.PasswordResetRequest;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.SupportTicket;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.repository.PasswordResetRequestRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.SquadMemberRepository;
import com.agilespace.backend.repository.SupportTicketReplyRepository;
import com.agilespace.backend.repository.SupportTicketRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JiraAccountIdGuard;
import com.agilespace.backend.security.UserSessionGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Endurecimento de acesso: id do Jira, chamados, reset de senha e cache de sessão")
class AccessHardeningTest {

    private User member() {
        return User.builder().id("u1").email("ana@totvs.com.br").name("Ana").role("MEMBER").active(true).build();
    }

    private UserService userService(JiraAccountIdGuard guard, User existing, UserSessionGuard sessionGuard) {
        UserService service = new UserService();
        UserRepository repo = mock(UserRepository.class);
        when(repo.findById("u1")).thenReturn(Optional.of(existing));
        when(repo.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        ReflectionTestUtils.setField(service, "userRepository", repo);
        ReflectionTestUtils.setField(service, "jiraAccountIdGuard", guard);
        ReflectionTestUtils.setField(service, "sessionGuard", sessionGuard);
        return service;
    }

    private JiraAccountIdGuard guardWithRoster(ProjectMemberRole... rows) {
        ProjectMemberRoleRepository pmr = mock(ProjectMemberRoleRepository.class);
        SquadMemberRepository sm = mock(SquadMemberRepository.class);
        when(pmr.findByJiraAccountId(any())).thenReturn(List.of(rows));
        when(sm.findByUserIdentifier(any())).thenReturn(List.of());
        return new JiraAccountIdGuard(pmr, sm);
    }

    @Test
    @DisplayName("MEMBER não grava no perfil o id do Jira de outra pessoa do roster")
    void memberCannotImpersonateRosterRow() {
        ProjectMemberRole amRow = ProjectMemberRole.builder().id("r1").projectId("SQ").jiraAccountId("jira-am")
                .email("am@totvs.com.br").isLeadership(true).build();
        UserService service = userService(guardWithRoster(amRow), member(), null);
        User incoming = User.builder().id("u1").jiraAccountId("jira-am").build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.saveUser(incoming, false));
        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    @DisplayName("MEMBER grava o id da própria linha ou um id fora do roster; admin sempre pode")
    void ownRowOrUnknownIdIsAllowed() {
        ProjectMemberRole mine = ProjectMemberRole.builder().id("r1").projectId("SQ").jiraAccountId("jira-ana")
                .email("ANA@totvs.com.br").build();
        assertEquals("jira-ana", userService(guardWithRoster(mine), member(), null)
                .saveUser(User.builder().id("u1").jiraAccountId("jira-ana").build(), false).getJiraAccountId());
        assertEquals("novo-id", userService(guardWithRoster(), member(), null)
                .saveUser(User.builder().id("u1").jiraAccountId("novo-id").build(), false).getJiraAccountId());

        ProjectMemberRole other = ProjectMemberRole.builder().id("r2").projectId("SQ").jiraAccountId("jira-x").email("x@totvs.com.br").build();
        assertEquals("jira-x", userService(guardWithRoster(other), member(), null)
                .saveUser(User.builder().id("u1").jiraAccountId("jira-x").build(), true).getJiraAccountId());
    }

    @Test
    @DisplayName("Salvar perfil invalida o cache de sessão da conta")
    void savingProfileEvictsSessionCache() {
        UserSessionGuard session = mock(UserSessionGuard.class);
        userService(guardWithRoster(), member(), session).saveUser(User.builder().id("u1").name("Ana B").build(), false);
        verify(session).evict("u1");
    }

    @Test
    @DisplayName("Chamado novo ignora id/status/autoria do corpo e valida tamanho")
    void ticketCreationIgnoresClientFields() {
        SupportTicketRepository tickets = mock(SupportTicketRepository.class);
        SupportTicketReplyRepository replies = mock(SupportTicketReplyRepository.class);
        when(tickets.save(any(SupportTicket.class))).thenAnswer(i -> i.getArgument(0));
        SupportTicketService service = new SupportTicketService(tickets, replies);

        SupportTicket body = SupportTicket.builder().id(UUID.randomUUID()).subject(" Erro ").message("Detalhe")
                .status("CLOSED").requesterId("vitima").build();
        SupportTicket created = service.createTicket("u1", "Ana", "ana@totvs.com.br", body);

        assertNull(created.getId(), "id vem do banco; um id do cliente faria o save tomar o chamado de outra pessoa");
        assertEquals("u1", created.getRequesterId());
        assertEquals("OPEN", created.getStatus());
        assertEquals("Erro", created.getSubject());

        assertThrows(ResponseStatusException.class, () -> service.createTicket("u1", "Ana", "e",
                SupportTicket.builder().subject("").message("x").build()));
        assertThrows(ResponseStatusException.class, () -> service.createTicket("u1", "Ana", "e",
                SupportTicket.builder().subject("s").message("m".repeat(8001)).build()));
    }

    @Test
    @DisplayName("Status de chamado desconhecido é recusado")
    void ticketStatusIsWhitelisted() {
        SupportTicketRepository tickets = mock(SupportTicketRepository.class);
        UUID id = UUID.randomUUID();
        when(tickets.findById(id)).thenReturn(Optional.of(SupportTicket.builder().id(id).subject("s").message("m").build()));
        SupportTicketService service = new SupportTicketService(tickets, mock(SupportTicketReplyRepository.class));
        assertThrows(ResponseStatusException.class, () -> service.updateStatus(id, "QUALQUER-COISA-MUITO-LONGA"));
    }

    @Test
    @DisplayName("Pedido de reset repetido com um pendente não cria outra linha")
    void resetRequestIsDeduplicated() {
        PasswordResetRequestRepository repo = mock(PasswordResetRequestRepository.class);
        PasswordResetRequest pending = PasswordResetRequest.builder().id("p1").userEmail("ana@totvs.com.br").status("PENDING").build();
        when(repo.findByStatusOrderByRequestedAtDesc("PENDING")).thenReturn(List.of(pending));
        PasswordResetService service = new PasswordResetService(repo, mock(UserRepository.class), mock(AuditLogRepository.class));

        assertSame(pending, service.requestReset(" ANA@totvs.com.br "));
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("Senha temporária some do registro depois de 1 hora")
    void tempPasswordIsWipedAfterAnHour() {
        PasswordResetRequestRepository repo = mock(PasswordResetRequestRepository.class);
        PasswordResetRequest old = PasswordResetRequest.builder().id("a").status("APPROVED").tempPassword("Abc123")
                .approvedAt(LocalDateTime.now().minusHours(2)).build();
        PasswordResetRequest fresh = PasswordResetRequest.builder().id("b").status("APPROVED").tempPassword("Xyz789")
                .approvedAt(LocalDateTime.now().minusMinutes(5)).build();
        when(repo.findAllByOrderByRequestedAtDesc()).thenReturn(List.of(old, fresh));
        PasswordResetService service = new PasswordResetService(repo, mock(UserRepository.class), mock(AuditLogRepository.class));

        service.getAllRequests();

        assertNull(old.getTempPassword());
        assertEquals("Xyz789", fresh.getTempPassword());
    }

    @Test
    @DisplayName("UserSessionGuard cacheia, devolve vazio para conta desconhecida e invalida no evict")
    void sessionGuardCaches() {
        UserRepository repo = mock(UserRepository.class);
        when(repo.findById("u1")).thenReturn(Optional.of(User.builder().id("u1").email("a@b.c").name("A").role("LEAD").active(false).build()));
        when(repo.findById("nobody")).thenReturn(Optional.empty());
        UserSessionGuard guard = new UserSessionGuard(repo);

        UserSessionGuard.State state = guard.lookup("u1").orElseThrow();
        assertFalse(state.active());
        assertEquals("LEAD", state.role());
        guard.lookup("u1");
        verify(repo, times(1)).findById("u1");

        guard.evict("u1");
        guard.lookup("u1");
        verify(repo, times(2)).findById("u1");

        assertTrue(guard.lookup("nobody").isEmpty());
        assertTrue(guard.lookup(null).isEmpty());
    }
}
