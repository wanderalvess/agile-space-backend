package com.agilespace.backend.service;

import com.agilespace.backend.domain.RetroBoard;
import com.agilespace.backend.domain.RetroCard;
import com.agilespace.backend.domain.RetroParticipant;
import com.agilespace.backend.repository.RetroBoardRepository;
import com.agilespace.backend.repository.RetroCardRepository;
import com.agilespace.backend.repository.RetroParticipantRepository;
import com.agilespace.backend.websocket.RetroWebSocketHandler;
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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RetroService - Gestão de Quadros, Participantes e Cartões da Retrospectiva")
class RetroServiceTest {

    @Mock
    private RetroBoardRepository boardRepository;

    @Mock
    private RetroParticipantRepository participantRepository;

    @Mock
    private RetroCardRepository cardRepository;

    @Mock
    private RetroWebSocketHandler webSocketHandler;

    @InjectMocks
    private RetroService service;

    private RetroBoard sampleBoard;

    @BeforeEach
    void setUp() {
        sampleBoard = RetroBoard.builder()
                .id("retro-123")
                .title("Retrospectiva Sprint 45")
                .columns(Arrays.asList("O que correu bem", "O que pode melhorar", "Ações"))
                .build();
    }

    @Nested
    @DisplayName("Gestão do Quadro de Retrospectiva")
    class BoardManagementTests {

        @Test
        @DisplayName("Deve listar quadros respeitando o limite seguro")
        void shouldListBoardsWithLimit() {
            when(boardRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.singletonList(sampleBoard)));

            List<RetroBoard> boards = service.listBoards(10);

            assertNotNull(boards);
            assertEquals(1, boards.size());
            assertEquals("retro-123", boards.get(0).getId());
            verify(boardRepository).findAll(any(Pageable.class));
        }

        @Test
        @DisplayName("Deve buscar quadro existente por ID")
        void shouldGetBoardById() {
            when(boardRepository.findById("retro-123")).thenReturn(Optional.of(sampleBoard));

            Optional<RetroBoard> result = service.getBoard("retro-123");

            assertTrue(result.isPresent());
            assertEquals("Retrospectiva Sprint 45", result.get().getTitle());
        }

        @Test
        @DisplayName("Deve salvar ou atualizar quadro e emitir evento BOARD_UPDATED")
        void shouldSaveOrUpdateBoardAndBroadcast() {
            when(boardRepository.save(sampleBoard)).thenReturn(sampleBoard);

            RetroBoard saved = service.saveOrUpdateBoard(sampleBoard);

            assertEquals("retro-123", saved.getId());
            verify(boardRepository).save(sampleBoard);
            verify(webSocketHandler).broadcastEvent(eq("retro-123"), eq("BOARD_UPDATED"), eq(sampleBoard));
        }
    }

    @Nested
    @DisplayName("Gestão de Participantes da Retrospectiva")
    class ParticipantTests {

        @Test
        @DisplayName("Deve listar participantes de um quadro")
        void shouldListParticipantsByBoard() {
            when(participantRepository.findByBoardId("retro-123"))
                    .thenReturn(Collections.singletonList(RetroParticipant.builder().nickname("Wanderson").build()));

            List<RetroParticipant> list = service.getParticipants("retro-123");

            assertEquals(1, list.size());
            assertEquals("Wanderson", list.get(0).getNickname());
        }

        @Test
        @DisplayName("Deve adicionar participante com ID composto gerado e notificar sala")
        void shouldAddOrUpdateParticipant() {
            RetroParticipant participant = RetroParticipant.builder()
                    .boardId("retro-123")
                    .id("user-dev1")
                    .nickname("Dev One")
                    .build();

            when(participantRepository.save(any(RetroParticipant.class))).thenAnswer(i -> i.getArgument(0));

            RetroParticipant saved = service.addOrUpdateParticipant(participant);

            assertEquals("retro-123_user-dev1", saved.getDbId());
            verify(participantRepository).save(participant);
            verify(webSocketHandler).broadcastEvent(eq("retro-123"), eq("PARTICIPANT_JOINED"), eq(saved));
        }

        @Test
        @DisplayName("Deve remover participante e notificar saída")
        void shouldRemoveParticipant() {
            service.removeParticipant("retro-123", "user-dev1");

            verify(participantRepository).deleteByBoardIdAndId("retro-123", "user-dev1");
            verify(webSocketHandler).broadcastEvent(eq("retro-123"), eq("PARTICIPANT_LEFT"), any());
        }
    }

    @Nested
    @DisplayName("Gestão e Isolamento de Cartões")
    class CardTests {

        @Test
        @DisplayName("Deve listar cartões associados ao quadro")
        void shouldGetCardsByBoard() {
            when(cardRepository.findByBoardId("retro-123"))
                    .thenReturn(Collections.singletonList(RetroCard.builder().id("c1").boardId("retro-123").build()));

            List<RetroCard> cards = service.getCards("retro-123");

            assertEquals(1, cards.size());
            assertEquals("c1", cards.get(0).getId());
        }

        @Test
        @DisplayName("Deve salvar cartão e transmitir evento CARD_SAVED")
        void shouldSaveCardAndBroadcast() {
            RetroCard card = RetroCard.builder().id("c1").boardId("retro-123").content("Boa comunicação").build();
            when(cardRepository.save(card)).thenReturn(card);

            RetroCard saved = service.saveOrUpdateCard(card);

            assertEquals("retro-123", saved.getBoardId());
            verify(cardRepository).save(card);
            verify(webSocketHandler).broadcastEvent(eq("retro-123"), eq("CARD_SAVED"), eq(saved));
        }

        @Test
        @DisplayName("Deve rejeitar atualização com HTTP 403 se o cartão pertencer a outro quadro")
        void shouldRejectSavingCardBelongingToAnotherBoard() {
            RetroCard existingCardInDb = RetroCard.builder().id("c1").boardId("outro-board").build();
            when(cardRepository.findById("c1")).thenReturn(Optional.of(existingCardInDb));

            RetroCard maliciousAttempt = RetroCard.builder().id("c1").boardId("retro-123").content("Tentativa indevida").build();

            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                    service.saveOrUpdateCard(maliciousAttempt)
            );

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
            assertTrue(ex.getReason().contains("outro board"));
            verify(cardRepository, never()).save(maliciousAttempt);
        }

        @Test
        @DisplayName("Deve excluir cartão e transmitir evento CARD_DELETED")
        void shouldDeleteCardAndBroadcast() {
            RetroCard card = RetroCard.builder().id("c-99").boardId("retro-123").build();
            when(cardRepository.findById("c-99")).thenReturn(Optional.of(card));

            service.deleteCard("retro-123", "c-99");

            verify(cardRepository).deleteById("c-99");
            verify(webSocketHandler).broadcastEvent(eq("retro-123"), eq("CARD_DELETED"), any());
        }

        @Test
        @DisplayName("Deve rejeitar exclusão com HTTP 403 se cartão não pertencer ao quadro informado")
        void shouldRejectDeleteCardFromDifferentBoard() {
            RetroCard card = RetroCard.builder().id("c-99").boardId("outro-quadro").build();
            when(cardRepository.findById("c-99")).thenReturn(Optional.of(card));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                    service.deleteCard("retro-123", "c-99")
            );

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
            verify(cardRepository, never()).deleteById("c-99");
        }

        @Test
        @DisplayName("Deve importar ações em lote e vincular todas ao quadro destino")
        void shouldImportActionsInBatch() {
            RetroCard c1 = RetroCard.builder().content("Ação 1").build();
            RetroCard c2 = RetroCard.builder().content("Ação 2").build();
            List<RetroCard> items = Arrays.asList(c1, c2);

            service.importActions("retro-123", items);

            assertEquals("retro-123", c1.getBoardId());
            assertEquals("retro-123", c2.getBoardId());
            verify(cardRepository, times(2)).save(any(RetroCard.class));
            verify(webSocketHandler).broadcastEvent(eq("retro-123"), eq("CARDS_IMPORTED"), eq(items));
        }
    }
}
