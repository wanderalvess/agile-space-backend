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
    @Mock private com.agilespace.backend.repository.ProjectConfigRepository projectConfigRepository;
    @Mock private com.agilespace.backend.repository.ProjectMemberRoleRepository projectMemberRoleRepository;
    @Mock private com.agilespace.backend.repository.UserRepository userRepository;
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
    @DisplayName("Aceitar convite cria o vínculo de projeto (sem liderança) e define a squad ativa")
    void acceptLinksProjectMembership() {
        when(inviteRepository.findByTokenForUpdate("tok")).thenReturn(Optional.of(pending(null, LocalDateTime.now().plusDays(1))));
        when(squadService.saveMember(anyString(), anyString(), any(SquadMember.class))).thenAnswer(i -> i.getArgument(2));
        when(projectConfigRepository.existsById("SQ")).thenReturn(true);
        when(projectMemberRoleRepository.findByProjectId("SQ")).thenReturn(java.util.List.of());

        User u = user();
        service.acceptInvite("tok", u);

        verify(projectMemberRoleRepository).save(argThat(m -> "SQ".equals(m.getProjectId()) && "u1".equals(m.getUserId()) && !m.isLeadership() && "INVITE".equals(m.getSource())));
        assertEquals("SQ", u.getDefaultProjectId());
        verify(userRepository).save(u);
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

    @Test
    @DisplayName("Página do convite recebe o nome da squad; sem projeto cadastrado, fica só a chave")
    void displayCarriesSquadName() {
        Invite invite = pending(null, LocalDateTime.now().plusDays(1));
        when(inviteRepository.findByToken("t1")).thenReturn(Optional.of(invite));
        com.agilespace.backend.domain.ProjectConfig project = new com.agilespace.backend.domain.ProjectConfig();
        project.setName("Time da Ana");
        when(projectConfigRepository.findById(invite.getSquadId())).thenReturn(Optional.of(project));
        assertEquals("Time da Ana", service.getForDisplay("t1", user()).getSquadName());

        Invite semProjeto = pending(null, LocalDateTime.now().plusDays(1));
        when(inviteRepository.findByToken("t2")).thenReturn(Optional.of(semProjeto));
        when(projectConfigRepository.findById(semProjeto.getSquadId())).thenReturn(Optional.empty());
        assertNull(service.getForDisplay("t2", user()).getSquadName());
    }

    @Test
    @DisplayName("Convite não concede liderança: Agile Master/Tech Lead (qualquer grafia) é rejeitado na criação")
    void createInviteRejectsLeadershipRoles() {
        for (String role : new String[] {"Agile Master", "tech lead", "AGILE_MASTER", "Scrum-Master", "Product Owner", "People Lead", "Tribe Lead", "Agile  Coach"}) {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.createInvite("SQ", role, null, "lead"), role);
            assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }
        verify(inviteRepository, never()).save(any());
    }

    @Test
    @DisplayName("Convite de liderança antigo (ainda pendente) não é aceito")
    void acceptRejectsLegacyLeadershipInvite() {
        Invite legacy = pending(null, LocalDateTime.now().plusDays(1));
        legacy.setRoleName("Agile Master");
        when(inviteRepository.findByTokenForUpdate("tok")).thenReturn(Optional.of(legacy));
        assertThrows(ResponseStatusException.class, () -> service.acceptInvite("tok", user()));
        verify(squadService, never()).saveMember(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Convite com e-mail: outro logado (nem membro) recebe 404; destinatário e membro veem completo")
    void displayHidesEmailBoundInviteFromStrangers() {
        Invite invite = pending("ana@totvs.com.br", LocalDateTime.now().plusDays(1));
        when(inviteRepository.findByToken("t")).thenReturn(Optional.of(invite));
        lenient().when(projectMemberRoleRepository.findByProjectId("SQ")).thenReturn(java.util.List.of());
        User stranger = User.builder().id("x").email("intruso@totvs.com.br").build();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.getForDisplay("t", stranger));
        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertThrows(ResponseStatusException.class, () -> service.getForDisplay("t", null));

        Invite seen = service.getForDisplay("t", user());
        assertEquals("ana@totvs.com.br", seen.getEmail());
        assertEquals("lead@totvs.com.br", seen.getInvitedBy());

        User member = User.builder().id("m").email("m@totvs.com.br").defaultProjectId("SQ").build();
        assertEquals("lead@totvs.com.br", service.getForDisplay("t", member).getInvitedBy());
    }

    @Test
    @DisplayName("Convite de link aberto: estranho vê só o necessário (sem e-mail nem quem convidou)")
    void displayOpenInviteIsSanitizedForStrangers() {
        Invite invite = pending(null, LocalDateTime.now().plusDays(1));
        when(inviteRepository.findByToken("t")).thenReturn(Optional.of(invite));
        when(projectMemberRoleRepository.findByProjectId("SQ")).thenReturn(java.util.List.of());
        Invite seen = service.getForDisplay("t", User.builder().id("x").email("x@totvs.com.br").build());
        assertEquals("Developer", seen.getRoleName());
        assertEquals("SQ", seen.getSquadId());
        assertNull(seen.getInvitedBy());
        assertNull(seen.getEmail());
    }

    @Test
    @DisplayName("Pré-visualização pública: squad e papel, sem quem convidou; e-mail só mascarado")
    void publicPreviewShowsSquadWithoutInviterAndMaskedEmail() {
        when(inviteRepository.findByToken("tok")).thenReturn(Optional.of(pending("ana@totvs.com.br", LocalDateTime.now().plusDays(1))));
        when(projectConfigRepository.findById("SQ")).thenReturn(Optional.of(
                com.agilespace.backend.domain.ProjectConfig.builder().id("SQ").name("Squad Alfa").build()));

        var view = service.getPublicPreview("tok");

        assertEquals("Squad Alfa", view.get("squadName"));
        assertFalse(view.containsKey("invitedByName"));
        assertEquals("Developer", view.get("roleName"));
        assertEquals("PENDING", view.get("status"));
        assertEquals("a••••@totvs.com.br", view.get("emailMasked"));
        assertFalse(view.containsKey("email"));
        assertFalse(view.containsKey("token"));
    }

    @Test
    @DisplayName("Pré-visualização pública: vencido vira EXPIRED e token inexistente é 404")
    void publicPreviewMarksExpiredAndRejectsUnknown() {
        when(inviteRepository.findByToken("tok")).thenReturn(Optional.of(pending(null, LocalDateTime.now().minusDays(1))));
        assertEquals("EXPIRED", service.getPublicPreview("tok").get("status"));
        assertNull(service.getPublicPreview("tok").get("emailMasked"));

        when(inviteRepository.findByToken("nada")).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.getPublicPreview("nada"));
        assertEquals(404, ex.getStatusCode().value());
    }
}
