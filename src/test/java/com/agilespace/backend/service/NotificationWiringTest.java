package com.agilespace.backend.service;

import com.agilespace.backend.domain.Invite;
import com.agilespace.backend.domain.PokerRoom;
import com.agilespace.backend.domain.RetroBoard;
import com.agilespace.backend.domain.RetroCard;
import com.agilespace.backend.repository.*;
import com.agilespace.backend.websocket.PokerWebSocketHandler;
import com.agilespace.backend.websocket.RetroWebSocketHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Os fluxos existentes chamam os gatilhos certos (o conteúdo da notificação é testado em NotificationTriggersTest). */
@ExtendWith(MockitoExtension.class)
@DisplayName("Gatilhos de notificação ligados a convite, poker assíncrono e ação de retro")
class NotificationWiringTest {

    @Mock private NotificationTriggers triggers;

    // Poker
    @Mock private PokerRoomRepository roomRepository;
    @Mock private PokerParticipantRepository pokerParticipantRepository;
    @Mock private PokerVoteRepository voteRepository;
    @Mock private PokerRoundRepository roundRepository;
    @Mock private PokerChatMessageRepository pokerChatRepository;
    @Mock private PokerWebSocketHandler pokerSocket;

    // Retro
    @Mock private RetroBoardRepository boardRepository;
    @Mock private RetroParticipantRepository retroParticipantRepository;
    @Mock private RetroCardRepository cardRepository;
    @Mock private RetroChatMessageRepository retroChatRepository;
    @Mock private RetroWebSocketHandler retroSocket;
    @Mock private SquadAccessService squadAccessService;

    // Convite
    @Mock private InviteRepository inviteRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private SquadService squadService;
    @Mock private ProjectConfigRepository projectConfigRepository;
    @Mock private ProjectMemberRoleRepository projectMemberRoleRepository;
    @Mock private UserRepository userRepository;

    private PokerService poker() {
        PokerService s = new PokerService(roomRepository, pokerParticipantRepository, voteRepository, roundRepository,
                pokerChatRepository, pokerSocket);
        s.setNotificationTriggers(triggers);
        return s;
    }

    @Test
    @DisplayName("Sala nova assíncrona: lembrete; sala síncrona: nada")
    void asyncRoomCreationTriggersReminder() {
        when(roomRepository.saveAndFlush(any(PokerRoom.class))).thenAnswer(i -> i.getArgument(0));
        PokerRoom async = new PokerRoom();
        async.setId("r1");
        async.setTitle("Refinamento");
        async.setTeam("SQ");
        async.setMode("async");
        when(roomRepository.findById("r1")).thenReturn(Optional.empty());
        poker().saveOrUpdateRoom(async, "ana", "MEMBER");
        verify(triggers).asyncPokerOpened("r1", "Refinamento", "SQ", "ana");

        PokerRoom sync = new PokerRoom();
        sync.setId("r2");
        sync.setMode("sync");
        when(roomRepository.findById("r2")).thenReturn(Optional.empty());
        poker().saveOrUpdateRoom(sync, "ana", "MEMBER");
        verify(triggers, never()).asyncPokerOpened(eq("r2"), any(), any(), any());
    }

    @Test
    @DisplayName("Card com responsável novo notifica; mesmo responsável não repete")
    void retroAssigneeTriggers() {
        RetroService retro = new RetroService(boardRepository, retroParticipantRepository, cardRepository,
                retroChatRepository, retroSocket, squadAccessService);
        retro.setNotificationTriggers(triggers);
        RetroBoard board = RetroBoard.builder().id("b1").title("Retro 41").squadId("SQ").creatorId("ana").build();
        when(boardRepository.findById("b1")).thenReturn(Optional.of(board));
        when(cardRepository.save(any(RetroCard.class))).thenAnswer(i -> i.getArgument(0));

        RetroCard card = RetroCard.builder().id("c1").boardId("b1").columnKey("actions").content("Revisar CI").assignee("Bob").build();
        when(cardRepository.findById("c1")).thenReturn(Optional.empty());
        retro.saveOrUpdateCard(card, new RetroCaller("ana", "MEMBER"));
        verify(triggers).retroActionAssigned("b1", "Retro 41", "SQ", "c1", "Revisar CI", "Bob", "ana");

        RetroCard stored = RetroCard.builder().id("c1").boardId("b1").columnKey("actions").content("Revisar CI")
                .assignee("Bob").authorId("ana").build();
        when(cardRepository.findById("c1")).thenReturn(Optional.of(stored));
        RetroCard edit = RetroCard.builder().id("c1").boardId("b1").content("Revisar CI e CD").assignee("bob").build();
        retro.saveOrUpdateCard(edit, new RetroCaller("ana", "MEMBER"));
        verify(triggers, times(1)).retroActionAssigned(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Convite criado chama o gatilho com token, squad e e-mails")
    void inviteTriggers() {
        InviteService invites = new InviteService(inviteRepository, auditLogRepository, squadService, projectConfigRepository,
                projectMemberRoleRepository, userRepository);
        invites.setNotificationTriggers(triggers);
        when(inviteRepository.save(any(Invite.class))).thenAnswer(i -> i.getArgument(0));

        Invite invite = invites.createInvite("SQ", "Developer", "bob@x.com", "lead@x.com");

        verify(triggers).inviteCreated(invite.getToken(), "SQ", null, "bob@x.com", "lead@x.com");
    }
}
