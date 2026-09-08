package com.agilespace.backend.service;

import com.agilespace.backend.domain.PokerParticipant;
import com.agilespace.backend.domain.PokerRoom;
import com.agilespace.backend.domain.PokerRound;
import com.agilespace.backend.domain.PokerVote;
import com.agilespace.backend.repository.PokerParticipantRepository;
import com.agilespace.backend.repository.PokerRoomRepository;
import com.agilespace.backend.repository.PokerRoundRepository;
import com.agilespace.backend.repository.PokerVoteRepository;
import com.agilespace.backend.websocket.PokerWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PokerService - Gestão de Salas, Votos e Rodadas do Planning Poker")
class PokerServiceTest {

    @Mock
    private PokerRoomRepository roomRepository;
    @Mock
    private PokerParticipantRepository participantRepository;
    @Mock
    private PokerVoteRepository voteRepository;
    @Mock
    private PokerRoundRepository roundRepository;
    @Mock
    private PokerWebSocketHandler webSocketHandler;

    @InjectMocks
    private PokerService service;

    private PokerRoom sampleRoom;

    @BeforeEach
    void setUp() {
        sampleRoom = PokerRoom.builder()
                .id("room-123")
                .title("Planning Poker Sprint 45")
                .creatorId("user-creator")
                .deckType("fibonacci")
                .build();
    }

    @Nested
    @DisplayName("Gestão de Salas de Poker")
    class RoomManagementTests {

        @Test
        @DisplayName("Deve buscar uma sala existente pelo identificador com sucesso")
        void shouldReturnRoomWhenExists() {
            when(roomRepository.findById("room-123")).thenReturn(Optional.of(sampleRoom));

            Optional<PokerRoom> result = service.getRoom("room-123");

            assertTrue(result.isPresent());
            assertEquals("room-123", result.get().getId());
            assertEquals("Planning Poker Sprint 45", result.get().getTitle());
            verify(roomRepository, times(1)).findById("room-123");
        }

        @Test
        @DisplayName("Deve retornar vazio quando a sala procurada não for encontrada")
        void shouldReturnEmptyWhenRoomNotFound() {
            when(roomRepository.findById("room-inexistente")).thenReturn(Optional.empty());

            Optional<PokerRoom> result = service.getRoom("room-inexistente");

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("Deve criar nova sala associando o callerId como criador e disparando broadcast")
        void shouldCreateNewRoomAndBroadcastEvent() {
            PokerRoom newRoom = PokerRoom.builder().title("Nova Sala").build();
            when(roomRepository.save(any(PokerRoom.class))).thenAnswer(i -> {
                PokerRoom r = i.getArgument(0);
                r.setId("room-nova");
                return r;
            });

            PokerRoom saved = service.saveOrUpdateRoom(newRoom, "user-creator", "MEMBER");

            assertEquals("room-nova", saved.getId());
            assertEquals("user-creator", saved.getCreatorId());
            verify(roomRepository).save(newRoom);
            verify(webSocketHandler).broadcastEvent(eq("room-nova"), eq("ROOM_UPDATED"), any(PokerRoom.class));
        }

        @Test
        @DisplayName("Deve permitir ao criador atualizar dados da sala existente")
        void shouldAllowCreatorToUpdateExistingRoom() {
            when(roomRepository.findById("room-123")).thenReturn(Optional.of(sampleRoom));
            when(roomRepository.save(sampleRoom)).thenReturn(sampleRoom);

            sampleRoom.setTitle("Planning Poker Sprint 45 - Revisado");
            PokerRoom updated = service.saveOrUpdateRoom(sampleRoom, "user-creator", "MEMBER");

            assertEquals("Planning Poker Sprint 45 - Revisado", updated.getTitle());
            verify(webSocketHandler).broadcastEvent(eq("room-123"), eq("ROOM_UPDATED"), eq(sampleRoom));
        }

        @Test
        @DisplayName("Deve permitir a um usuário com papel ADMIN atualizar qualquer sala existente")
        void shouldAllowAdminToUpdateRoomEvenIfNotParticipant() {
            when(roomRepository.findById("room-123")).thenReturn(Optional.of(sampleRoom));
            when(roomRepository.save(sampleRoom)).thenReturn(sampleRoom);

            PokerRoom updated = service.saveOrUpdateRoom(sampleRoom, "admin-user", "ADMIN");

            assertNotNull(updated);
            verify(roomRepository).save(sampleRoom);
        }

        @Test
        @DisplayName("Deve rejeitar com HTTP 403 quando usuário de fora tentar atualizar a sala")
        void shouldRejectUpdateFromOutsiderWithForbidden() {
            when(roomRepository.findById("room-123")).thenReturn(Optional.of(sampleRoom));
            when(participantRepository.existsById("room-123_outsider-user")).thenReturn(false);

            ResponseStatusException exception = assertThrows(ResponseStatusException.class, () ->
                    service.saveOrUpdateRoom(sampleRoom, "outsider-user", "MEMBER")
            );

            assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
            assertTrue(exception.getReason().contains("Apenas participantes"));
            verify(roomRepository, never()).save(sampleRoom);
        }

        @Test
        @DisplayName("Deve listar salas respeitando o limite fornecido")
        void shouldListRoomsWithLimit() {
            when(roomRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.singletonList(sampleRoom)));

            List<PokerRoom> rooms = service.listRooms(10);

            assertNotNull(rooms);
            assertEquals(1, rooms.size());
            assertEquals("room-123", rooms.get(0).getId());
        }
    }

    @Nested
    @DisplayName("Gestão de Participantes e Presença")
    class ParticipantTests {

        @Test
        @DisplayName("Deve registrar entrada de participante gerando ID composto e notificando sala")
        void shouldJoinRoomAndBroadcastPresence() {
            PokerParticipant participant = PokerParticipant.builder()
                    .roomId("room-123")
                    .id("user-wanderson")
                    .nickname("Wanderson")
                    .role("SCRUM_MASTER")
                    .build();

            when(participantRepository.save(any(PokerParticipant.class))).thenAnswer(i -> i.getArgument(0));

            PokerParticipant joined = service.joinRoom(participant);

            assertEquals("room-123_user-wanderson", joined.getDbId());
            verify(participantRepository).save(participant);
            verify(webSocketHandler).broadcastEvent(eq("room-123"), eq("PARTICIPANT_JOINED"), eq(joined));
        }

        @Test
        @DisplayName("Deve listar participantes ordenados pelo apelido")
        void shouldListParticipantsByRoom() {
            when(participantRepository.findByRoomIdOrderByNicknameAsc("room-123"))
                    .thenReturn(Collections.singletonList(PokerParticipant.builder().nickname("Wanderson").build()));

            List<PokerParticipant> participants = service.getParticipants("room-123");

            assertEquals(1, participants.size());
            assertEquals("Wanderson", participants.get(0).getNickname());
        }

        @Test
        @DisplayName("Deve atualizar heartbeat do participante quando ele estiver ativo na sala")
        void shouldUpdateParticipantHeartbeat() {
            PokerParticipant participant = PokerParticipant.builder()
                    .dbId("room-123_user-1")
                    .build();

            when(participantRepository.findById("room-123_user-1")).thenReturn(Optional.of(participant));
            when(participantRepository.save(any(PokerParticipant.class))).thenAnswer(i -> i.getArgument(0));

            service.updateHeartbeat("room-123", "user-1");

            assertNotNull(participant.getLastSeen());
            verify(participantRepository).save(participant);
        }

        @Test
        @DisplayName("Deve remover participante e notificar desconexão na sala")
        void shouldLeaveRoomAndBroadcastEvent() {
            service.leaveRoom("room-123", "user-1");

            verify(participantRepository).deleteByRoomIdAndId("room-123", "user-1");
            verify(webSocketHandler).broadcastEvent(eq("room-123"), eq("PARTICIPANT_LEFT"), any());
        }
    }

    @Nested
    @DisplayName("Gestão de Votos")
    class VoteTests {

        @Test
        @DisplayName("Deve salvar voto com chave composta quando o próprio usuário votar")
        void shouldSaveVoteForOwner() {
            PokerVote vote = PokerVote.builder()
                    .roomId("room-123")
                    .participantId("user-dev1")
                    .value("8")
                    .build();

            when(voteRepository.save(any(PokerVote.class))).thenAnswer(i -> i.getArgument(0));

            PokerVote saved = service.saveVote(vote, "user-dev1");

            assertEquals("room-123_user-dev1", saved.getId());
            assertEquals("8", saved.getValue());
            verify(voteRepository).save(vote);
            verify(webSocketHandler).broadcastEvent(eq("room-123"), eq("VOTE_SAVED"), eq(saved));
        }

        @Test
        @DisplayName("Deve rejeitar com HTTP 403 tentativa de votar em nome de outro usuário")
        void shouldRejectVotingOnBehalfOfOther() {
            PokerVote vote = PokerVote.builder()
                    .roomId("room-123")
                    .participantId("user-dev1")
                    .value("5")
                    .build();

            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                    service.saveVote(vote, "impostor-user")
            );

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
            assertTrue(ex.getReason().contains("próprio voto"));
            verify(voteRepository, never()).save(any());
        }

        @Test
        @DisplayName("Deve permitir limpar votos da sala quando solicitada pelo criador")
        void shouldClearVotesWhenRequestedByCreator() {
            when(roomRepository.findById("room-123")).thenReturn(Optional.of(sampleRoom));

            service.clearVotes("room-123", "user-creator", "MEMBER");

            verify(voteRepository).deleteByRoomId("room-123");
            verify(webSocketHandler).broadcastEvent(eq("room-123"), eq("VOTES_CLEARED"), any());
        }

        @Test
        @DisplayName("Deve remover voto individual e notificar clientes")
        void shouldRemoveIndividualVote() {
            service.removeVote("room-123", "user-1");

            verify(voteRepository).deleteByRoomIdAndParticipantId("room-123", "user-1");
            verify(webSocketHandler).broadcastEvent(eq("room-123"), eq("VOTE_REMOVED"), any());
        }
    }

    @Nested
    @DisplayName("Gestão de Rodadas e Histórico")
    class RoundTests {

        @Test
        @DisplayName("Deve salvar rodada gerando UUID e disparar evento ROUND_SAVED")
        void shouldSaveRoundAndGenerateId() {
            PokerRound round = PokerRound.builder()
                    .roomId("room-123")
                    .topic("Card AS-101 Implementar OAuth")
                    .devPoints("5")
                    .build();

            when(roundRepository.save(any(PokerRound.class))).thenAnswer(i -> i.getArgument(0));

            PokerRound saved = service.saveRound(round);

            assertNotNull(saved.getId());
            assertEquals("room-123", saved.getRoomId());
            assertEquals("5", saved.getDevPoints());
            verify(roundRepository).save(round);
            verify(webSocketHandler).broadcastEvent(eq("room-123"), eq("ROUND_SAVED"), eq(saved));
        }

        @Test
        @DisplayName("Deve recuperar rodadas recentes com limite de paginação")
        void shouldGetRoundsWithLimit() {
            when(roundRepository.findRecentRounds(eq("room-123"), any(Pageable.class)))
                    .thenReturn(Collections.singletonList(PokerRound.builder().roomId("room-123").build()));

            List<PokerRound> rounds = service.getRounds("room-123", 5);

            assertEquals(1, rounds.size());
            verify(roundRepository).findRecentRounds(eq("room-123"), any(Pageable.class));
        }

        @Test
        @DisplayName("Deve limpar rodadas da sala quando solicitado pelo criador")
        void shouldClearRoundsWhenAuthorized() {
            when(roomRepository.findById("room-123")).thenReturn(Optional.of(sampleRoom));

            service.clearRounds("room-123", "user-creator", "MEMBER");

            verify(roundRepository).deleteByRoomId("room-123");
            verify(webSocketHandler).broadcastEvent(eq("room-123"), eq("ROUNDS_CLEARED"), any());
        }

        @Test
        @DisplayName("Deve enviar reação imediata via WebSocket sem tocar no banco")
        void shouldBroadcastReaction() {
            service.sendReaction("room-123", "🎉");

            verify(webSocketHandler).broadcastReaction("room-123", "🎉");
            verifyNoInteractions(roundRepository, voteRepository);
        }
    }
}
