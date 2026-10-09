package com.agilespace.backend.service;

import com.agilespace.backend.domain.Invite;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.repository.InviteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InviteService - criação, aceite único e revogação")
class InviteServiceTest {

    @Mock private InviteRepository inviteRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private SquadService squadService;
    @InjectMocks private InviteService service;

    private User user() {
        return User.builder().id("u1").email("ana@totvs.com.br").name("Ana").build();
    }

    private Invite pending(String email, LocalDateTime expires) {
        return Invite.builder().id("i1").token("tok").squadId("SQ").roleName("Developer")
                .email(email).invitedBy("lead@totvs.com.br").status("PENDING").expiresAt(expires).build();
    }

    @Test
    @DisplayName("Token é opaco (32 hex), expira em 7 dias e papel é obrigatório")
    void createInviteBuildsOpaqueToken() {
        when(inviteRepository.save(any(Invite.class))).thenAnswer(i -> i.getArgument(0));
        Invite invite = service.createInvite("SQ", " Developer ", "ANA@TOTVS.COM.BR", "lead");
        assertTrue(invite.getToken().matches("[0-9a-f]{32}"));
        assertEquals("Developer", invite.getRoleName());
        assertEquals("ana@totvs.com.br", invite.getEmail());
        assertTrue(invite.getExpiresAt().isAfter(LocalDateTime.now().plusDays(6)));

        assertThrows(ResponseStatusException.class, () -> service.createInvite("SQ", "  ", null, "lead"));
        assertThrows(ResponseStatusException.class, () -> service.createInvite("SQ", "x".repeat(81), null, "lead"));
        assertThrows(ResponseStatusException.class, () -> service.createInvite("SQ", "Dev", "sem-arroba", "lead"));
    }

    @Test
    @DisplayName("Aceite usa a leitura com trava e marca ACCEPTED")
    void acceptUsesLockedReadAndMarksAccepted() {
        when(inviteRepository.findByTokenForUpdate("tok")).thenReturn(Optional.of(pending(null, LocalDateTime.now().plusDays(1))));
        when(squadService.saveMember(anyString(), anyString(), any(SquadMember.class))).thenAnswer(i -> i.getArgument(2));

        service.acceptInvite("tok", user());

        verify(inviteRepository).findByTokenForUpdate("tok");
        verify(inviteRepository).save(argThat(i -> "ACCEPTED".equals(i.getStatus()) && "u1".equals(i.getAcceptedByUserId())));
    }

    @Test
    @DisplayName("Convite já usado, expirado ou de outro e-mail é recusado")
    void acceptRejectsUsedExpiredOrWrongEmail() {
        Invite used = pending(null, LocalDateTime.now().plusDays(1));
        used.setStatus("ACCEPTED");
        when(inviteRepository.findByTokenForUpdate("used")).thenReturn(Optional.of(used));
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.acceptInvite("used", user())).getStatusCode().value());

        when(inviteRepository.findByTokenForUpdate("old")).thenReturn(Optional.of(pending(null, LocalDateTime.now().minusMinutes(1))));
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.acceptInvite("old", user())).getStatusCode().value());

        when(inviteRepository.findByTokenForUpdate("other")).thenReturn(Optional.of(pending("outra@totvs.com.br", LocalDateTime.now().plusDays(1))));
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.acceptInvite("other", user())).getStatusCode().value());

        verify(squadService, never()).saveMember(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Só convite pendente pode ser revogado")
    void revokeOnlyPending() {
        Invite accepted = pending(null, LocalDateTime.now().plusDays(1));
        accepted.setStatus("ACCEPTED");
        when(inviteRepository.findById("i1")).thenReturn(Optional.of(accepted));
        assertThrows(ResponseStatusException.class, () -> service.revoke("i1", "SQ", "lead"));
        assertEquals("ACCEPTED", accepted.getStatus());

        Invite open = pending(null, LocalDateTime.now().plusDays(1));
        when(inviteRepository.findById("i2")).thenReturn(Optional.of(open));
        service.revoke("i2", "SQ", "lead");
        assertEquals("REVOKED", open.getStatus());
    }
}
