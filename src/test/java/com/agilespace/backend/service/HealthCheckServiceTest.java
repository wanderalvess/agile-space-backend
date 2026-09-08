package com.agilespace.backend.service;

import com.agilespace.backend.domain.HealthCheckBoard;
import com.agilespace.backend.domain.HealthCheckParticipant;
import com.agilespace.backend.domain.HealthCheckVote;
import com.agilespace.backend.repository.HealthCheckBoardRepository;
import com.agilespace.backend.repository.HealthCheckParticipantRepository;
import com.agilespace.backend.repository.HealthCheckVoteRepository;
import com.agilespace.backend.websocket.HealthCheckWebSocketHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("HealthCheckService - Gestão do Squad Health Check, Dimensões e Votos do Time")
class HealthCheckServiceTest {

    @Mock
    private HealthCheckBoardRepository boardRepository;

    @Mock
    private HealthCheckParticipantRepository participantRepository;

    @Mock
    private HealthCheckVoteRepository voteRepository;

    @Mock
    private HealthCheckWebSocketHandler webSocketHandler;

    @InjectMocks
    private HealthCheckService service;

    @Nested
    @DisplayName("Gestão de Quadros de Health Check")
    class BoardTests {

        @Test
        @DisplayName("Deve buscar quadro existente por ID")
        void shouldGetBoardById() {
            HealthCheckBoard board = HealthCheckBoard.builder().id("hc1").status("active").build();
            when(boardRepository.findById("hc1")).thenReturn(Optional.of(board));

            Optional<HealthCheckBoard> result = service.getBoard("hc1");

            assertTrue(result.isPresent());
            assertEquals("active", result.get().getStatus());
        }

        @Test
        @DisplayName("Deve criar quadro gerando UUID e transmitindo evento WebSocket")
        void shouldSaveOrUpdateBoardAndBroadcast() {
            HealthCheckBoard board = HealthCheckBoard.builder().status("planning").build();
            when(boardRepository.save(any(HealthCheckBoard.class))).thenAnswer(i -> i.getArgument(0));

            HealthCheckBoard saved = service.saveOrUpdateBoard(board);

            assertNotNull(saved.getId());
            assertNotNull(saved.getCreatedAt());
            verify(boardRepository).save(board);
            verify(webSocketHandler).broadcastEvent(eq(saved.getId()), eq("BOARD_UPDATED"), eq(saved));
        }

        @Test
        @DisplayName("Deve excluir quadro e cascatear remoção de votos e participantes")
        void shouldDeleteBoardAndCascadeChildren() {
            service.deleteBoard("hc1");

            verify(voteRepository).deleteByBoardId("hc1");
            verify(participantRepository).deleteByBoardId("hc1");
            verify(boardRepository).deleteById("hc1");
            verify(webSocketHandler).broadcastEvent(eq("hc1"), eq("BOARD_DELETED"), any());
        }
    }

    @Nested
    @DisplayName("Participação e Presença")
    class ParticipantTests {

        @Test
        @DisplayName("Deve permitir entrada de participante com chave composta boardId_userId")
        void shouldJoinBoardWithCompositeKey() {
            HealthCheckParticipant p = HealthCheckParticipant.builder().boardId("hc1").id("u1").nickname("Dev").build();
            when(participantRepository.save(any(HealthCheckParticipant.class))).thenAnswer(i -> i.getArgument(0));

            HealthCheckParticipant saved = service.joinBoard(p);

            assertEquals("hc1_u1", saved.getDbId());
            verify(participantRepository).save(p);
            verify(webSocketHandler).broadcastEvent(eq("hc1"), eq("PARTICIPANT_JOINED"), eq(saved));
        }

        @Test
        @DisplayName("Deve processar saída do participante e notificar sala")
        void shouldLeaveBoardAndBroadcast() {
            service.leaveBoard("hc1", "u1");

            verify(participantRepository).deleteByBoardIdAndId("hc1", "u1");
            verify(webSocketHandler).broadcastEvent(eq("hc1"), eq("PARTICIPANT_LEFT"), any());
        }
    }

    @Nested
    @DisplayName("Votação por Dimensão (Spotify Health Check)")
    class VoteTests {

        @Test
        @DisplayName("Deve salvar voto gerando ID único baseado em boardId, participante e dimensão")
        void shouldSaveVoteWithDimensionCompositeKey() {
            HealthCheckVote vote = HealthCheckVote.builder()
                    .boardId("hc1")
                    .participantId("u1")
                    .dimensionKey("delivery_value")
                    .value("green")
                    .build();

            when(voteRepository.save(any(HealthCheckVote.class))).thenAnswer(i -> i.getArgument(0));

            HealthCheckVote saved = service.saveVote(vote);

            assertEquals("hc1_u1_delivery_value", saved.getId());
            assertEquals("green", saved.getValue());
            verify(voteRepository).save(vote);
            verify(webSocketHandler).broadcastEvent(eq("hc1"), eq("VOTE_SAVED"), eq(saved));
        }

        @Test
        @DisplayName("Deve recuperar votos de um participante específico na sessão")
        void shouldGetUserVotes() {
            when(voteRepository.findByBoardIdAndParticipantId("hc1", "u1"))
                    .thenReturn(Collections.singletonList(new HealthCheckVote()));

            List<HealthCheckVote> list = service.getUserVotes("hc1", "u1");

            assertEquals(1, list.size());
            verify(voteRepository).findByBoardIdAndParticipantId("hc1", "u1");
        }
    }
}
