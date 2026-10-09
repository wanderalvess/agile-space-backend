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
import static org.mockito.ArgumentMatchers.anyString;
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
            when(roomRepository.saveAndFlush(any(PokerRoom.class))).thenAnswer(i -> {
                PokerRoom r = i.getArgument(0);
                r.setId("room-nova");
                return r;
            });

            PokerRoom saved = service.saveOrUpdateRoom(newRoom, "user-creator", "MEMBER");

            assertEquals("room-nova", saved.getId());
            assertEquals("user-creator", saved.getCreatorId());
            verify(roomRepository).saveAndFlush(newRoom);
            verify(webSocketHandler).broadcastEvent(eq("room-nova"), eq("ROOM_UPDATED"), any(PokerRoom.class));
        }

        @Test
        @DisplayName("Deve permitir ao criador atualizar dados da sala existente")
        void shouldAllowCreatorToUpdateExistingRoom() {
            when(roomRepository.findById("room-123")).thenReturn(Optional.of(sampleRoom));
            when(roomRepository.saveAndFlush(sampleRoom)).thenReturn(sampleRoom);

            sampleRoom.setTitle("Planning Poker Sprint 45 - Revisado");
            PokerRoom updated = service.saveOrUpdateRoom(sampleRoom, "user-creator", "MEMBER");

            assertEquals("Planning Poker Sprint 45 - Revisado", updated.getTitle());
            verify(webSocketHandler).broadcastEvent(eq("room-123"), eq("ROOM_UPDATED"), eq(sampleRoom));
        }

        @Test
        @DisplayName("Deve permitir a um usuário com papel ADMIN atualizar qualquer sala existente")
        void shouldAllowAdminToUpdateRoomEvenIfNotParticipant() {
            when(roomRepository.findById("room-123")).thenReturn(Optional.of(sampleRoom));
            when(roomRepository.saveAndFlush(sampleRoom)).thenReturn(sampleRoom);

            PokerRoom updated = service.saveOrUpdateRoom(sampleRoom, "admin-user", "ADMIN");

            assertNotNull(updated);
            verify(roomRepository).saveAndFlush(sampleRoom);
        }

        @Test
        @DisplayName("Gravação concorrente: o conflito de versão chega ao controller (não é engolido) e nada é transmitido")
        void shouldPropagateOptimisticLockWithoutBroadcast() {
            when(roomRepository.findById("room-123")).thenReturn(Optional.of(sampleRoom));
            when(roomRepository.saveAndFlush(sampleRoom))
                    .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(PokerRoom.class, "room-123"));

            assertThrows(org.springframework.dao.OptimisticLockingFailureException.class,
                    () -> service.saveOrUpdateRoom(sampleRoom, "user-creator", "MEMBER"));
            verify(webSocketHandler, never()).broadcastEvent(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("Cliente sem versão (antigo/MCP) em sala existente herda a versão atual em vez de virar INSERT")
        void shouldKeepWorkingForClientsThatDoNotSendVersion() {
            PokerRoom stored = PokerRoom.builder().id("room-123").creatorId("user-creator").version(7L).build();
            PokerRoom incoming = PokerRoom.builder().id("room-123").title("Sem versão").build();
            when(roomRepository.findById("room-123")).thenReturn(Optional.of(stored));
            when(roomRepository.saveAndFlush(incoming)).thenReturn(incoming);

            service.saveOrUpdateRoom(incoming, "user-creator", "MEMBER");

            assertEquals(7L, incoming.getVersion());
        }

        @Test
        @DisplayName("Cliente com versão antiga mantém a versão enviada: o banco decide o conflito")
        void shouldNotOverrideVersionSentByClient() {
            PokerRoom stored = PokerRoom.builder().id("room-123").creatorId("user-creator").version(7L).build();
            PokerRoom incoming = PokerRoom.builder().id("room-123").version(5L).build();
            when(roomRepository.findById("room-123")).thenReturn(Optional.of(stored));
            when(roomRepository.saveAndFlush(incoming)).thenReturn(incoming);

            service.saveOrUpdateRoom(incoming, "user-creator", "MEMBER");

            assertEquals(5L, incoming.getVersion());
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
        @DisplayName("Deve listar salas da squad respeitando o limite fornecido")
        void shouldListRoomsWithLimit() {
            sampleRoom.setTeam("DDWMISSI");
            when(roomRepository.findByTeamIgnoreCaseOrderByCreatedAtDesc(eq("DDWMISSI"), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.singletonList(sampleRoom)));

            List<PokerRoom> rooms = service.listRooms(10, "DDWMISSI");

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

            when(roomRepository.findById("room-123")).thenReturn(Optional.of(sampleRoom));
            when(participantRepository.findById("room-123_user-wanderson")).thenAnswer(i -> Optional.of(participant));

            PokerParticipant joined = service.joinRoom(participant, "user-wanderson", "MEMBER");

            assertEquals("room-123_user-wanderson", joined.getDbId());
            // upsert idempotente: duas entradas simultâneas não violam a PK
            verify(participantRepository).upsertParticipant(
                    eq("room-123_user-wanderson"), eq("user-wanderson"), eq("room-123"), eq("Wanderson"),
                    any(), eq("SCRUM_MASTER"), any(), any(), anyString());
            // lastSeen deve ser UTC de verdade (sufixo Z), não hora local rotulada como Z
            assertTrue(joined.getLastSeen().endsWith("Z"));
            long driftMs = Math.abs(java.time.Instant.parse(joined.getLastSeen()).toEpochMilli() - System.currentTimeMillis());
            assertTrue(driftMs < 5000, "lastSeen fora do instante atual: " + driftMs + "ms");
            verify(webSocketHandler).broadcastEvent(eq("room-123"), eq("PARTICIPANT_JOINED"), eq(joined));
        }

        private PokerParticipant forged(String id, boolean facilitator) {
            return PokerParticipant.builder().roomId("room-123").id(id).nickname("X").role("dev").isFacilitator(facilitator).build();
        }

        @Test
        @DisplayName("Não deve permitir entrar na sala como outro usuário")
        void shouldRejectJoinAsAnotherUser() {
            when(roomRepository.findById("room-123")).thenReturn(Optional.of(sampleRoom));
            when(participantRepository.findById("room-123_intruso")).thenReturn(Optional.empty());

            ResponseStatusException e = assertThrows(ResponseStatusException.class,
                    () -> service.joinRoom(forged("vitima", false), "intruso", "MEMBER"));
            assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
            verify(participantRepository, never()).upsertParticipant(any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Não deve deixar um participante comum se promover a facilitador")
        void shouldIgnoreSelfDeclaredFacilitator() {
            when(roomRepository.findById("room-123")).thenReturn(Optional.of(sampleRoom));
            when(participantRepository.findById("room-123_user-b")).thenReturn(Optional.empty());

            PokerParticipant p = forged("user-b", true);
            service.joinRoom(p, "user-b", "MEMBER");

            verify(participantRepository).upsertParticipant(
                    eq("room-123_user-b"), eq("user-b"), eq("room-123"), any(), any(), any(), eq(false), any(), anyString());
        }

        @Test
        @DisplayName("Criador da sala entra como facilitador")
        void shouldKeepFacilitatorForCreator() {
            when(roomRepository.findById("room-123")).thenReturn(Optional.of(sampleRoom));

            service.joinRoom(forged("user-creator", true), "user-creator", "MEMBER");

            verify(participantRepository).upsertParticipant(
                    eq("room-123_user-creator"), eq("user-creator"), eq("room-123"), any(), any(), any(), eq(true), any(), anyString());
        }

        @Test
        @DisplayName("Facilitador pode editar o papel de outro participante")
        void shouldAllowFacilitatorToEditOthers() {
            when(roomRepository.findById("room-123")).thenReturn(Optional.of(sampleRoom));

            service.joinRoom(forged("user-b", false), "user-creator", "MEMBER");

            verify(participantRepository).upsertParticipant(
                    eq("room-123_user-b"), eq("user-b"), eq("room-123"), any(), any(), any(), any(), any(), anyString());
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

            service.updateHeartbeat("room-123", "user-1");

            // só a coluna last_seen é gravada (não a entidade inteira)
            org.mockito.ArgumentCaptor<String> ts = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(participantRepository).updateLastSeen(eq("room-123_user-1"), ts.capture());
            long driftMs = Math.abs(java.time.Instant.parse(ts.getValue()).toEpochMilli() - System.currentTimeMillis());
            assertTrue(driftMs < 5000, "heartbeat fora do instante atual: " + driftMs + "ms");
            verify(participantRepository, never()).save(any());
        }

        @Test
        @DisplayName("Deve remover participante e notificar desconexão na sala")
        void shouldLeaveRoomAndBroadcastEvent() {
            service.leaveRoom("room-123", "user-1", "user-1", "MEMBER");

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

            when(roomRepository.findById("room-123")).thenReturn(Optional.of(sampleRoom));
            when(participantRepository.existsById("room-123_user-dev1")).thenReturn(true);
            when(voteRepository.save(any(PokerVote.class))).thenAnswer(i -> i.getArgument(0));

            PokerVote saved = service.saveVote(vote, "user-dev1");

            assertEquals("room-123_user-dev1", saved.getId());
            assertEquals("8", saved.getValue());
            verify(voteRepository).save(vote);
            verify(webSocketHandler).broadcastEvent(eq("room-123"), eq("VOTE_SAVED"), eq(saved));
        }

        private PokerVote vote(String participant, String value, String issueId) {
            return PokerVote.builder().roomId("room-123").participantId(participant).value(value).issueId(issueId).build();
        }

        @Test
        @DisplayName("Deve rejeitar voto depois da revelação")
        void shouldRejectVoteAfterReveal() {
            sampleRoom.setVotesRevealed(true);
            when(roomRepository.findById("room-123")).thenReturn(Optional.of(sampleRoom));
            when(participantRepository.existsById("room-123_u1")).thenReturn(true);

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.saveVote(vote("u1", "5", null), "u1"));
            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
            verify(voteRepository, never()).save(any());
        }

        @Test
        @DisplayName("Deve rejeitar carta que não existe no baralho e voto de quem não está na sala")
        void shouldRejectInvalidCardAndOutsider() {
            when(roomRepository.findById("room-123")).thenReturn(Optional.of(sampleRoom));
            when(participantRepository.existsById("room-123_u1")).thenReturn(true);
            when(participantRepository.existsById("room-123_u2")).thenReturn(false);

            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                    () -> service.saveVote(vote("u1", "4", null), "u1")).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                    () -> service.saveVote(vote("u2", "5", null), "u2")).getStatusCode());
        }

        @Test
        @DisplayName("Sala assíncrona guarda um voto por tarefa e exige a tarefa")
        void shouldKeepOneVotePerIssueInAsyncRoom() {
            sampleRoom.setMode("async");
            when(roomRepository.findById("room-123")).thenReturn(Optional.of(sampleRoom));
            when(participantRepository.existsById("room-123_u1")).thenReturn(true);
            when(voteRepository.save(any(PokerVote.class))).thenAnswer(i -> i.getArgument(0));

            PokerVote a = service.saveVote(vote("u1", "5", "issue-a"), "u1");
            PokerVote b = service.saveVote(vote("u1", "8", "issue-b"), "u1");

            assertEquals("room-123_u1_issue-a", a.getId());
            assertEquals("room-123_u1_issue-b", b.getId());
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                    () -> service.saveVote(vote("u1", "5", null), "u1")).getStatusCode());
        }

        @Test
        @DisplayName("Atualizar só a confiança preserva apelido, papel e tarefa do voto")
        void shouldKeepVoteMetadataWhenUpdatingConfidence() {
            sampleRoom.setActiveIssueId("issue-1");
            when(roomRepository.findById("room-123")).thenReturn(Optional.of(sampleRoom));
            when(participantRepository.existsById("room-123_u1")).thenReturn(true);
            when(voteRepository.findById("room-123_u1")).thenReturn(Optional.of(PokerVote.builder()
                    .id("room-123_u1").roomId("room-123").participantId("u1").value("5")
                    .participantNickname("Ana").participantRole("dev").participantGlobalRole("Developer").issueId("issue-1").build()));
            when(voteRepository.save(any(PokerVote.class))).thenAnswer(i -> i.getArgument(0));

            PokerVote update = vote("u1", "5", null);
            update.setConfidence("high");
            PokerVote saved = service.saveVote(update, "u1");

            assertEquals("Ana", saved.getParticipantNickname());
            assertEquals("dev", saved.getParticipantRole());
            assertEquals("issue-1", saved.getIssueId());
            assertEquals("high", saved.getConfidence());
        }

        @Test
        @DisplayName("Facilitador remove voto de outro; participante comum não")
        void shouldLetOnlyFacilitatorRemoveOthersVote() {
            when(roomRepository.findById("room-123")).thenReturn(Optional.of(sampleRoom));
            when(participantRepository.findById("room-123_u2")).thenReturn(Optional.of(
                    PokerParticipant.builder().id("u2").roomId("room-123").role("dev").isFacilitator(false).build()));

            assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                    () -> service.removeVote("room-123", "u1", null, "u2", "MEMBER")).getStatusCode());

            service.removeVote("room-123", "u1", null, "user-creator", "MEMBER");
            verify(voteRepository).deleteByRoomIdAndParticipantId("room-123", "u1");
        }

        @Test
        @DisplayName("Votos às cegas: terceiros veem '*' até a revelação; o próprio e o facilitador veem o valor")
        void shouldMaskVotesInBlindRooms() throws Exception {
            sampleRoom.setSettings(new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"blindVotes\":true}"));
            when(roomRepository.findById("room-123")).thenReturn(Optional.of(sampleRoom));
            when(voteRepository.findByRoomId("room-123")).thenReturn(List.of(
                    PokerVote.builder().id("room-123_u1").roomId("room-123").participantId("u1").value("5").build(),
                    PokerVote.builder().id("room-123_u2").roomId("room-123").participantId("u2").value("8").build()));

            List<PokerVote> seenByU1 = service.getVotes("room-123", "u1", "MEMBER");
            assertEquals("5", seenByU1.get(0).getValue());
            assertEquals("*", seenByU1.get(1).getValue());
            assertEquals("8", service.getVotes("room-123", "user-creator", "MEMBER").get(1).getValue());

            sampleRoom.setVotesRevealed(true);
            assertEquals("8", service.getVotes("room-123", "u3", "MEMBER").get(1).getValue());
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
            when(roomRepository.findById("room-123")).thenReturn(Optional.of(sampleRoom));

            service.removeVote("room-123", "user-1", null, "user-1", "MEMBER");

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

            when(roomRepository.findById("room-123")).thenReturn(Optional.of(sampleRoom));
            when(roundRepository.save(any(PokerRound.class))).thenAnswer(i -> i.getArgument(0));

            PokerRound saved = service.saveRound(round, "user-creator", "MEMBER");

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
            when(roomRepository.findById("room-123")).thenReturn(Optional.of(sampleRoom));

            service.sendReaction("room-123", "{\"emoji\":\"🎉\",\"uid\":\"forjado\"}", "user-creator", "MEMBER");

            // remetente é o do JWT, nunca o do corpo
            verify(webSocketHandler).broadcastReaction(eq("room-123"), org.mockito.ArgumentMatchers.argThat(json ->
                    json.contains("\"uid\":\"user-creator\"") && json.contains("🎉") && !json.contains("forjado")));
            verifyNoInteractions(roundRepository, voteRepository);
        }
    }
}
