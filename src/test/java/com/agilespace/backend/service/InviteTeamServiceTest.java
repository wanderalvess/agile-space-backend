package com.agilespace.backend.service;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.domain.Invite;
import com.agilespace.backend.domain.InviteRedemption;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.repository.InviteRedemptionRepository;
import com.agilespace.backend.repository.InviteRepository;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InviteService - convite de equipe (link multiuso)")
class InviteTeamServiceTest {

    @Mock private InviteRepository inviteRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private SquadService squadService;
    @Mock private ProjectConfigRepository projectConfigRepository;
    @Mock private ProjectMemberRoleRepository projectMemberRoleRepository;
    @Mock private UserRepository userRepository;
    @Mock private InviteRedemptionRepository redemptionRepository;
    @InjectMocks private InviteService service;

    @org.junit.jupiter.api.BeforeEach
    void wire() {
        service.setRedemptionRepository(redemptionRepository);
    }

    private User user(String id) {
        return User.builder().id(id).email(id + "@totvs.com.br").name("Pessoa " + id).build();
    }

    private Invite team(Integer max, int used, LocalDateTime expires) {
        return Invite.builder().id("t1").token("tok").squadId("SQ").roleName("Developer").invitedBy("lead@totvs.com.br")
                .status("PENDING").multiUse(true).maxUses(max).usesCount(used).expiresAt(expires).build();
    }

    private void stubJoin() {
        lenient().when(squadService.saveMember(anyString(), anyString(), any(SquadMember.class))).thenAnswer(i -> i.getArgument(2));
        lenient().when(projectConfigRepository.existsById("SQ")).thenReturn(true);
        lenient().when(projectMemberRoleRepository.findByProjectId("SQ")).thenReturn(List.of());
    }

    // ---- criação ----

    @Test
    @DisplayName("Criação: papel fixo, sem e-mail, limite e 7 dias por padrão; token opaco")
    void createsTeamInvite() {
        when(inviteRepository.save(any(Invite.class))).thenAnswer(i -> i.getArgument(0));
        Invite invite = service.createTeamInvite("SQ", " QA ", 25, null, "lead", false);
        assertTrue(invite.isMultiUse());
        assertNull(invite.getEmail());
        assertEquals("QA", invite.getRoleName());
        assertEquals(25, invite.getMaxUses());
        assertEquals(0, invite.getUsesCount());
        assertTrue(invite.getToken().matches("[0-9a-f]{32}"));
        assertTrue(invite.getExpiresAt().isAfter(LocalDateTime.now().plusDays(6)));
        assertTrue(invite.getExpiresAt().isBefore(LocalDateTime.now().plusDays(8)));
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(cap.capture());
        assertEquals("INVITE_CREATED", cap.getValue().getAction());
        assertFalse(cap.getValue().getDetails().contains(invite.getToken()));
    }

    @Test
    @DisplayName("Criação: limites de pessoas (1..200) e validade (1..30); sem limite só para ADMIN")
    void createLimits() {
        lenient().when(inviteRepository.save(any(Invite.class))).thenAnswer(i -> i.getArgument(0));
        for (Integer bad : new Integer[]{0, -1, 201}) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                    () -> service.createTeamInvite("SQ", "Developer", bad, 7, "lead", true)).getStatusCode());
        }
        for (Integer bad : new Integer[]{0, 31}) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                    () -> service.createTeamInvite("SQ", "Developer", 10, bad, "lead", true)).getStatusCode());
        }
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.createTeamInvite("SQ", "Developer", null, 7, "lead", false)).getStatusCode());
        assertNull(service.createTeamInvite("SQ", "Developer", null, 30, "admin", true).getMaxUses());
        assertEquals(200, service.createTeamInvite("SQ", "Developer", 200, 1, "lead", false).getMaxUses());
    }

    @Test
    @DisplayName("Criação: papel de liderança é proibido em link multiuso")
    void createRejectsLeadershipRoles() {
        for (String role : new String[]{"Agile Master", "tech lead", "Product Owner", "People Lead", "", "  "}) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                    () -> service.createTeamInvite("SQ", role, 5, 7, "lead", true), role).getStatusCode());
        }
        verify(inviteRepository, never()).save(any());
    }

    // ---- aceite ----

    @Test
    @DisplayName("Aceite consome 1 uso, grava a passagem, o vínculo (INVITE, sem liderança) e a auditoria INVITE_ACCEPTED")
    void acceptConsumesOneUse() {
        stubJoin();
        when(inviteRepository.findByTokenForUpdate("tok")).thenReturn(Optional.of(team(25, 3, LocalDateTime.now().plusDays(2))));
        when(redemptionRepository.existsByInviteIdAndUserId("t1", "u1")).thenReturn(false);
        when(inviteRepository.consumeUse(eq("t1"), any())).thenReturn(1);
        User u = user("u1");

        service.acceptInvite("tok", u);

        verify(inviteRepository).consumeUse(eq("t1"), any(LocalDateTime.class));
        verify(redemptionRepository).saveAndFlush(argThat((InviteRedemption r) -> "t1".equals(r.getInviteId()) && "u1".equals(r.getUserId())));
        verify(projectMemberRoleRepository).save(argThat((ProjectMemberRole m) ->
                "SQ".equals(m.getProjectId()) && "u1".equals(m.getUserId()) && !m.isLeadership() && "INVITE".equals(m.getSource())));
        assertEquals("SQ", u.getDefaultProjectId());
        // O convite de equipe nunca é regravado como ACCEPTED nem salvo (uses_count só muda pela UPDATE condicional).
        verify(inviteRepository, never()).save(any());
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(cap.capture());
        assertEquals("INVITE_ACCEPTED", cap.getValue().getAction());
        assertEquals("u1", cap.getValue().getPerformedBy());
        for (String parte : new String[]{"userId=u1", "email=u1@totvs.com.br", "squad=SQ", "papel=Developer", "conviteId=t1", "origem=INVITE"}) {
            assertTrue(cap.getValue().getDetails().contains(parte), parte);
        }
        assertFalse(cap.getValue().getDetails().contains("tok;"));
    }

    @Test
    @DisplayName("Mesma conta não consome dois usos: já usou o link")
    void sameAccountDoesNotConsumeTwice() {
        when(inviteRepository.findByTokenForUpdate("tok")).thenReturn(Optional.of(team(25, 3, LocalDateTime.now().plusDays(2))));
        when(redemptionRepository.existsByInviteIdAndUserId("t1", "u1")).thenReturn(true);

        SquadMember m = service.acceptInvite("tok", user("u1"));

        assertEquals("SQ", m.getSquadId());
        verify(inviteRepository, never()).consumeUse(anyString(), any());
        verify(redemptionRepository, never()).saveAndFlush(any());
        verify(squadService, never()).saveMember(anyString(), anyString(), any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("Mesma conta não consome uso: já é membro da squad por outro caminho")
    void alreadyMemberDoesNotConsume() {
        when(inviteRepository.findByTokenForUpdate("tok")).thenReturn(Optional.of(team(1, 0, LocalDateTime.now().plusDays(2))));
        when(redemptionRepository.existsByInviteIdAndUserId("t1", "u1")).thenReturn(false);
        when(projectMemberRoleRepository.findByProjectId("SQ")).thenReturn(List.of(
                ProjectMemberRole.builder().projectId("SQ").userId("u1").roleName("QA").build()));

        service.acceptInvite("tok", user("u1"));

        verify(inviteRepository, never()).consumeUse(anyString(), any());
        verify(squadService, never()).saveMember(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Esgotado (UPDATE condicional devolve 0): 409 com a mensagem de limite e ninguém entra")
    void exhausted() {
        when(inviteRepository.findByTokenForUpdate("tok")).thenReturn(Optional.of(team(2, 2, LocalDateTime.now().plusDays(2))));
        when(projectMemberRoleRepository.findByProjectId("SQ")).thenReturn(List.of());
        when(inviteRepository.consumeUse(eq("t1"), any())).thenReturn(0);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.acceptInvite("tok", user("u9")));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("atingiu o limite de pessoas"));
        verify(squadService, never()).saveMember(anyString(), anyString(), any());
        verify(redemptionRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Expirado e revogado: 400, sem consumir; quem já entrou continua (nada é removido)")
    void expiredAndRevoked() {
        when(inviteRepository.findByTokenForUpdate("old")).thenReturn(Optional.of(team(5, 0, LocalDateTime.now().minusMinutes(1))));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class, () -> service.acceptInvite("old", user("u1"))).getStatusCode());

        Invite revoked = team(5, 1, LocalDateTime.now().plusDays(1));
        revoked.setStatus("REVOKED");
        revoked.setRevokedAt(LocalDateTime.now());
        when(inviteRepository.findByTokenForUpdate("rev")).thenReturn(Optional.of(revoked));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class, () -> service.acceptInvite("rev", user("u1"))).getStatusCode());

        verify(inviteRepository, never()).consumeUse(anyString(), any());
        verify(squadService, never()).saveMember(anyString(), anyString(), any());
        verify(projectMemberRoleRepository, never()).delete(any());
        verify(projectMemberRoleRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("Revogar convite de equipe grava revoked_at")
    void revokeSetsRevokedAt() {
        Invite invite = team(5, 1, LocalDateTime.now().plusDays(1));
        when(inviteRepository.findById("t1")).thenReturn(Optional.of(invite));
        service.revoke("t1", "SQ", "lead");
        assertEquals("REVOKED", invite.getStatus());
        assertNotNull(invite.getRevokedAt());
    }

    @Test
    @DisplayName("Disputa pelo último uso: duas pessoas ao mesmo tempo, só uma entra (UPDATE condicional atômica)")
    void lastUseRace() throws Exception {
        stubJoin();
        Invite invite = team(1, 0, LocalDateTime.now().plusDays(2));
        lenient().when(inviteRepository.findByTokenForUpdate("tok")).thenReturn(Optional.of(invite));
        AtomicInteger used = new AtomicInteger(0);
        // Simula o banco: o teste-e-incremento é atômico, como a UPDATE ... WHERE uses_count < max_uses.
        when(inviteRepository.consumeUse(eq("t1"), any())).thenAnswer(i -> {
            synchronized (used) {
                if (used.get() < 1) { used.incrementAndGet(); return 1; }
                return 0;
            }
        });
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger full = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Thread[] threads = new Thread[2];
        for (int n = 0; n < 2; n++) {
            User u = user("u" + n);
            threads[n] = new Thread(() -> {
                try {
                    start.await();
                    service.acceptInvite("tok", u);
                    ok.incrementAndGet();
                } catch (ResponseStatusException e) {
                    if (e.getStatusCode() == HttpStatus.CONFLICT) full.incrementAndGet(); else unexpected.set(e);
                } catch (Throwable t) {
                    unexpected.set(t);
                }
            });
            threads[n].start();
        }
        start.countDown();
        for (Thread t : threads) t.join();

        assertNull(unexpected.get());
        assertEquals(1, ok.get());
        assertEquals(1, full.get());
        assertEquals(1, used.get());
        verify(squadService, times(1)).saveMember(anyString(), anyString(), any());
        verify(redemptionRepository, times(1)).saveAndFlush(any());
    }

    // ---- pré-visualização e página ----

    @Test
    @DisplayName("Preview público: multiUse, remainingUses e status (PENDING/EXHAUSTED/EXPIRED/REVOKED), sem token, e-mail nem quem convidou")
    void publicPreview() {
        when(projectConfigRepository.findById("SQ")).thenReturn(Optional.of(
                com.agilespace.backend.domain.ProjectConfig.builder().id("SQ").name("Squad Alfa").build()));

        when(inviteRepository.findByToken("a")).thenReturn(Optional.of(team(25, 18, LocalDateTime.now().plusDays(2))));
        var open = service.getPublicPreview("a");
        assertEquals(true, open.get("multiUse"));
        assertEquals(7, open.get("remainingUses"));
        assertEquals("PENDING", open.get("status"));
        assertEquals("Squad Alfa", open.get("squadName"));
        assertNull(open.get("emailMasked"));
        for (String k : new String[]{"token", "invitedBy", "invitedByName", "email", "usesCount", "maxUses"}) assertFalse(open.containsKey(k), k);

        when(inviteRepository.findByToken("b")).thenReturn(Optional.of(team(25, 25, LocalDateTime.now().plusDays(2))));
        assertEquals("EXHAUSTED", service.getPublicPreview("b").get("status"));
        assertEquals(0, service.getPublicPreview("b").get("remainingUses"));

        when(inviteRepository.findByToken("c")).thenReturn(Optional.of(team(25, 1, LocalDateTime.now().minusHours(1))));
        assertEquals("EXPIRED", service.getPublicPreview("c").get("status"));

        Invite rev = team(25, 1, LocalDateTime.now().plusDays(1));
        rev.setStatus("REVOKED");
        when(inviteRepository.findByToken("d")).thenReturn(Optional.of(rev));
        assertEquals("REVOKED", service.getPublicPreview("d").get("status"));

        when(inviteRepository.findByToken("e")).thenReturn(Optional.of(team(null, 40, LocalDateTime.now().plusDays(1))));
        var unlimited = service.getPublicPreview("e");
        assertNull(unlimited.get("remainingUses"));
        assertEquals("PENDING", unlimited.get("status"));
    }

    @Test
    @DisplayName("Preview do convite individual segue igual e traz multiUse=false")
    void publicPreviewSingleUseUnchanged() {
        Invite single = Invite.builder().id("i1").token("tok").squadId("SQ").roleName("QA").invitedBy("x").status("PENDING")
                .expiresAt(LocalDateTime.now().plusDays(1)).build();
        when(inviteRepository.findByToken("s")).thenReturn(Optional.of(single));
        var view = service.getPublicPreview("s");
        assertEquals(false, view.get("multiUse"));
        assertEquals("PENDING", view.get("status"));
        assertEquals(1, view.get("remainingUses"));
    }

    @Test
    @DisplayName("Página logada: alreadyMember sem consumir; quem não é da squad não vê usesCount/maxUses nem quem convidou")
    void displayForLoggedUser() {
        Invite invite = team(25, 18, LocalDateTime.now().plusDays(2));
        when(inviteRepository.findByToken("t")).thenReturn(Optional.of(invite));
        when(projectMemberRoleRepository.findByProjectId("SQ")).thenReturn(List.of(
                ProjectMemberRole.builder().projectId("SQ").userId("m1").roleName("QA").build()));
        when(redemptionRepository.existsByInviteIdAndUserId(anyString(), anyString())).thenReturn(false);

        Invite novo = service.getForDisplay("t", user("x"));
        assertEquals(Boolean.FALSE, novo.getAlreadyMember());
        assertEquals(7, novo.getRemainingUses());
        assertNull(novo.getInvitedBy());
        assertNull(novo.getMaxUses());

        Invite membro = service.getForDisplay("t", user("m1"));
        assertEquals(Boolean.TRUE, membro.getAlreadyMember());
        assertEquals(25, membro.getMaxUses());
        assertEquals(18, membro.getUsesCount());
        verify(inviteRepository, never()).consumeUse(anyString(), any());
    }

    @Test
    @DisplayName("Quem entrou: lista nome e data para quem criou; convite individual não tem lista")
    void listRedemptions() {
        when(inviteRepository.findById("t1")).thenReturn(Optional.of(team(5, 2, LocalDateTime.now().plusDays(1))));
        LocalDateTime joined = LocalDateTime.now().minusHours(1);
        when(redemptionRepository.findByInviteIdOrderByCreatedAtAsc("t1")).thenReturn(List.of(
                InviteRedemption.builder().id("r1").inviteId("t1").userId("u1").createdAt(joined).build()));
        when(userRepository.findAllById(any())).thenReturn(List.of(user("u1")));

        var rows = service.listRedemptions("t1", "SQ");
        assertEquals(1, rows.size());
        assertEquals("Pessoa u1", rows.get(0).get("name"));
        assertEquals(joined, rows.get(0).get("joinedAt"));

        Invite single = Invite.builder().id("s1").squadId("SQ").roleName("QA").invitedBy("x").expiresAt(LocalDateTime.now().plusDays(1)).build();
        when(inviteRepository.findById("s1")).thenReturn(Optional.of(single));
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, () -> service.listRedemptions("s1", "SQ")).getStatusCode());
    }
}
