package com.agilespace.backend.service;

import com.agilespace.backend.domain.RetroParticipant;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.RetroParticipantRepository;
import com.agilespace.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationTriggers - destinatários, responsável por nome e falha isolada")
class NotificationTriggersTest {

    @Mock private NotificationService notificationService;
    @Mock private SquadAudienceService audienceService;
    @Mock private UserRepository userRepository;
    @Mock private RetroParticipantRepository retroParticipantRepository;
    private NotificationTriggers triggers;

    @BeforeEach
    void setup() {
        // Sem gerenciador de transação: fora de transação o gatilho roda na hora (AfterCommit).
        triggers = new NotificationTriggers(notificationService, audienceService, userRepository, retroParticipantRepository, null);
    }

    private static User user(String id, String name, String email) {
        return User.builder().id(id).name(name).email(email).build();
    }

    @Test
    @DisplayName("Poker assíncrono: membros da squad, autor de fora, 1 por sala")
    void asyncPokerReminder() {
        when(audienceService.memberUserIdsOf(List.of("SQ"))).thenReturn(Set.of("ana", "bob"));
        triggers.asyncPokerOpened("r1", "Refinamento", "SQ", "ana");
        verify(notificationService).notifyUsers(eq(Set.of("ana", "bob")),
                argThat(d -> "REMINDER".equals(d.kind()) && "ana".equals(d.actorId()) && "POKER_ASYNC:r1".equals(d.dedupeKey())
                        && "/room/r1".equals(d.link())),
                eq(UserSignalService.NOTIFICATION));
    }

    @Test
    @DisplayName("Convite: só para conta existente com o e-mail convidado; autor resolvido pelo e-mail")
    void inviteForExistingAccount() {
        when(userRepository.findActiveByEmails(Set.of("bob@x.com"))).thenReturn(List.of(user("bob", "Bob", "bob@x.com")));
        when(userRepository.findActiveByEmails(Set.of("lead@x.com"))).thenReturn(List.of(user("lead", "Lead", "lead@x.com")));
        triggers.inviteCreated("tok", "SQ", "Squad Azul", "Bob@X.com", "lead@x.com");
        verify(notificationService).notifyUsers(eq(List.of("bob")),
                argThat(d -> "INVITE".equals(d.kind()) && "lead".equals(d.actorId()) && d.text().contains("Squad Azul")
                        && "/invite/tok".equals(d.link())),
                any());

        when(userRepository.findActiveByEmails(Set.of("novo@x.com"))).thenReturn(List.of());
        triggers.inviteCreated("tok2", "SQ", "Squad Azul", "novo@x.com", "lead@x.com");
        verify(notificationService, times(1)).notifyUsers(any(), any(), any());
    }

    @Test
    @DisplayName("Ação de retro: apelido do participante resolve para a conta")
    void retroAssigneeByParticipantNickname() {
        RetroParticipant p = new RetroParticipant();
        p.setId("bob");
        p.setNickname("Bobby");
        when(retroParticipantRepository.findByBoardId("b1")).thenReturn(List.of(p));
        triggers.retroActionAssigned("b1", "Retro Sprint 41", "SQ", "c1", "Revisar pipeline", " bobby ", "ana");
        verify(notificationService).notifyUsers(eq(List.of("bob")),
                argThat(d -> "ACTION_ASSIGNED".equals(d.kind()) && "Retro Sprint 41".equals(d.title()) && "/retro/b1".equals(d.link())),
                any());
    }

    @Test
    @DisplayName("Ação de retro: nome/e-mail/usuário de membro da squad; ambíguo ou desconhecido não notifica")
    void resolveAssigneeAmongSquadMembers() {
        when(audienceService.memberUserIdsOf(List.of("SQ"))).thenReturn(Set.of("u1", "u2", "u3"));
        when(userRepository.findActiveByIds(Set.of("u1", "u2", "u3"))).thenReturn(List.of(
                user("u1", "Carla Dias", "carla.dias@totvs.com.br"),
                user("u2", "João", "joao1@totvs.com.br"),
                user("u3", "João", "joao2@totvs.com.br")));

        assertEquals("u1", triggers.resolveAssignee("carla dias", List.of(), "SQ"));
        assertEquals("u1", triggers.resolveAssignee("carla.dias", List.of(), "SQ"));
        assertEquals("u2", triggers.resolveAssignee("joao1@totvs.com.br", List.of(), "SQ"));
        assertEquals("u3", triggers.resolveAssignee("u3", List.of(), "SQ"));
        assertNull(triggers.resolveAssignee("João", List.of(), "SQ"));
        assertNull(triggers.resolveAssignee("Fulano", List.of(), "SQ"));
        assertNull(triggers.resolveAssignee("Carla Dias", List.of(), null));
    }

    @Test
    @DisplayName("Falha ao notificar só vira log: a chamada não lança")
    void failureIsIsolated() {
        when(audienceService.memberUserIdsOf(any())).thenThrow(new IllegalStateException("banco fora"));
        assertDoesNotThrow(() -> triggers.asyncPokerOpened("r1", "Sala", "SQ", "ana"));
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("Aviso: público por tipo; agendado ou expirado não notifica")
    void announcementAudience() {
        when(audienceService.memberUserIdsOf(List.of("SQA"))).thenReturn(Set.of("bob"));
        triggers.announcementPublished("a1", "Janela de manutenção", "SQUADS", List.of("SQA"), List.of(),
                LocalDateTime.now().minusMinutes(1), null, null, "adm");
        verify(notificationService).notifyUsers(eq(Set.of("bob")),
                argThat(d -> "ANNOUNCEMENT".equals(d.kind()) && "ANNOUNCEMENT:a1".equals(d.dedupeKey()) && "SQA".equals(d.squadId())),
                eq(UserSignalService.ANNOUNCEMENT));

        triggers.announcementPublished("a2", "Futuro", "ALL", List.of(), List.of(),
                LocalDateTime.now().plusDays(1), null, null, "adm");
        triggers.announcementPublished("a3", "Expirado", "ALL", List.of(), List.of(),
                LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(1), null, "adm");
        verify(audienceService, never()).allActiveUserIds();

        when(audienceService.userIdsWithRoles(List.of("AGILE_MASTER"))).thenReturn(Set.of("am"));
        triggers.announcementPublished("a4", "Para AMs", "ROLES", List.of(), List.of("AGILE_MASTER"), null, null, null, "adm");
        verify(notificationService).notifyUsers(eq(Set.of("am")), argThat(d -> "ANNOUNCEMENT:a4".equals(d.dedupeKey())), any());
    }

    @Test
    @DisplayName("Pedido de vínculo criado: avisa os ADMINs ativos com link /admin, 1 por pedido")
    void linkRequestCreatedNotifiesAdmins() {
        when(userRepository.findActiveIdsByRoles(Set.of("ADMIN"))).thenReturn(List.of("adm1", "adm2"));
        triggers.linkRequestCreated("lr1", "ana", "Ana Paula", "DDWMISSI");
        verify(notificationService).notifyUsers(eq(List.of("adm1", "adm2")),
                argThat(d -> "LINK_REQUEST".equals(d.kind()) && "/admin".equals(d.link()) && "ana".equals(d.actorId())
                        && "LINK_REQUEST_CREATED:lr1".equals(d.dedupeKey()) && d.text().contains("Ana Paula")
                        && d.text().contains("DDWMISSI")),
                eq(UserSignalService.NOTIFICATION));

        triggers.linkRequestCreated(null, "ana", "Ana", "X");
        verify(notificationService, times(1)).notifyUsers(any(), any(), any());
    }

    @Test
    @DisplayName("Pedido de vínculo decidido: aprovado leva ao /painel, recusado ao /onboarding com o motivo")
    void linkRequestDecidedNotifiesRequester() {
        triggers.linkRequestDecided("lr1", "ana", true, "DDWMISSI", null, "adm");
        verify(notificationService).notifyUsers(eq(List.of("ana")),
                argThat(d -> "LINK_REQUEST".equals(d.kind()) && "/painel".equals(d.link()) && "adm".equals(d.actorId())
                        && "LINK_REQUEST_DECIDED:lr1".equals(d.dedupeKey()) && d.text().contains("aprovado")),
                eq(UserSignalService.NOTIFICATION));

        triggers.linkRequestDecided("lr2", "ana", false, "DDWMISSI", "  e-mail\nnão confere ", "adm");
        verify(notificationService).notifyUsers(eq(List.of("ana")),
                argThat(d -> "/onboarding".equals(d.link()) && "LINK_REQUEST_DECIDED:lr2".equals(d.dedupeKey())
                        && d.text().contains("recusado") && d.text().contains("Motivo: e-mail não confere")),
                eq(UserSignalService.NOTIFICATION));

        triggers.linkRequestDecided("lr3", "ana", false, "DDWMISSI", null, "adm");
        verify(notificationService).notifyUsers(eq(List.of("ana")),
                argThat(d -> "LINK_REQUEST_DECIDED:lr3".equals(d.dedupeKey()) && !d.text().contains("Motivo")), any());

        triggers.linkRequestDecided(null, "ana", true, "X", null, "adm");
        triggers.linkRequestDecided("lr4", null, true, "X", null, "adm");
        verify(notificationService, times(3)).notifyUsers(any(), any(), any());
    }
}
