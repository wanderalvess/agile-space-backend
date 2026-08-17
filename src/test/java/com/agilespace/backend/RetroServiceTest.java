package com.agilespace.backend;

import com.agilespace.backend.domain.RetroBoard;
import com.agilespace.backend.domain.RetroCard;
import com.agilespace.backend.domain.RetroParticipant;
import com.agilespace.backend.repository.RetroBoardRepository;
import com.agilespace.backend.repository.RetroCardRepository;
import com.agilespace.backend.repository.RetroParticipantRepository;
import com.agilespace.backend.service.RetroService;
import com.agilespace.backend.websocket.RetroWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class RetroServiceTest {

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

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testListBoards() {
        when(boardRepository.findAll()).thenReturn(Arrays.asList(new RetroBoard()));
        List<RetroBoard> list = service.listBoards();
        assertEquals(1, list.size());
    }

    @Test
    public void testGetBoard() {
        RetroBoard board = RetroBoard.builder().id("r1").build();
        when(boardRepository.findById("r1")).thenReturn(Optional.of(board));
        Optional<RetroBoard> result = service.getBoard("r1");
        assertTrue(result.isPresent());
    }

    @Test
    public void testSaveOrUpdateBoardTriggersBroadcast() {
        RetroBoard board = RetroBoard.builder().id("retro-123").title("Retro Sprint 1").build();
        when(boardRepository.save(board)).thenReturn(board);

        RetroBoard saved = service.saveOrUpdateBoard(board);

        assertEquals("Retro Sprint 1", saved.getTitle());
        verify(webSocketHandler, times(1)).broadcastEvent(eq("retro-123"), eq("BOARD_UPDATED"), any());
        verify(boardRepository, times(1)).save(board);
    }

    @Test
    public void testGetParticipants() {
        when(participantRepository.findByBoardId("r1")).thenReturn(Arrays.asList(new RetroParticipant()));
        List<RetroParticipant> list = service.getParticipants("r1");
        assertEquals(1, list.size());
    }

    @Test
    public void testAddOrUpdateParticipantGeneratesDbId() {
        RetroParticipant participant = RetroParticipant.builder()
                .boardId("retro-123")
                .id("user-456")
                .nickname("Wanderson")
                .build();

        when(participantRepository.save(any(RetroParticipant.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RetroParticipant saved = service.addOrUpdateParticipant(participant);

        assertEquals("retro-123_user-456", saved.getDbId());
        verify(webSocketHandler, times(1)).broadcastEvent(eq("retro-123"), eq("PARTICIPANT_JOINED"), any());
    }

    @Test
    public void testRemoveParticipant() {
        service.removeParticipant("r1", "u1");
        verify(participantRepository).deleteByBoardIdAndId("r1", "u1");
        verify(webSocketHandler).broadcastEvent(eq("r1"), eq("PARTICIPANT_LEFT"), any());
    }

    @Test
    public void testGetCards() {
        when(cardRepository.findByBoardId("r1")).thenReturn(Arrays.asList(new RetroCard()));
        List<RetroCard> list = service.getCards("r1");
        assertEquals(1, list.size());
    }

    @Test
    public void testSaveOrUpdateCard() {
        RetroCard card = RetroCard.builder().boardId("r1").build();
        when(cardRepository.save(any(RetroCard.class))).thenAnswer(i -> i.getArgument(0));

        RetroCard saved = service.saveOrUpdateCard(card);

        assertEquals("r1", saved.getBoardId());
        verify(webSocketHandler).broadcastEvent(eq("r1"), eq("CARD_SAVED"), any());
    }

    @Test
    public void testDeleteCardTriggersBroadcast() {
        RetroCard card = RetroCard.builder()
                .id("card-999")
                .boardId("retro-123")
                .content("O que correu bem")
                .build();

        when(cardRepository.findById("card-999")).thenReturn(Optional.of(card));
        doNothing().when(cardRepository).deleteById("card-999");

        service.deleteCard("card-999");

        verify(cardRepository, times(1)).deleteById("card-999");
        verify(webSocketHandler, times(1)).broadcastEvent(eq("retro-123"), eq("CARD_DELETED"), any());
    }

    @Test
    public void testImportActions() {
        RetroCard card1 = RetroCard.builder().content("Action 1").build();
        RetroCard card2 = RetroCard.builder().content("Action 2").build();
        List<RetroCard> cards = Arrays.asList(card1, card2);

        service.importActions("r1", cards);

        assertEquals("r1", card1.getBoardId());
        assertEquals("r1", card2.getBoardId());
        verify(cardRepository, times(2)).save(any(RetroCard.class));
        verify(webSocketHandler).broadcastEvent(eq("r1"), eq("CARDS_IMPORTED"), any());
    }
}
