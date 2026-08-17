package com.agilespace.backend.controller;

import com.agilespace.backend.domain.RetroBoard;
import com.agilespace.backend.domain.RetroParticipant;
import com.agilespace.backend.domain.RetroCard;
import com.agilespace.backend.service.RetroService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class RetroControllerTest {

    @Mock
    private RetroService service;

    @InjectMocks
    private RetroController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testGetBoard() {
        RetroBoard board = new RetroBoard();
        when(service.getBoard("123")).thenReturn(Optional.of(board));
        
        ResponseEntity<RetroBoard> response = controller.getBoard("123");
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testSaveOrUpdateBoard() {
        RetroBoard board = new RetroBoard();
        when(service.saveOrUpdateBoard(board)).thenReturn(board);
        
        ResponseEntity<RetroBoard> response = controller.saveOrUpdateBoard(board);
        
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
    }

    @Test
    public void testAddOrUpdateParticipant() {
        RetroParticipant participant = new RetroParticipant();
        when(service.addOrUpdateParticipant(participant)).thenReturn(participant);
        
        ResponseEntity<RetroParticipant> response = controller.addOrUpdateParticipant("123", participant);
        
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("123", participant.getBoardId());
    }

    @Test
    public void testSaveOrUpdateCard() {
        RetroCard card = new RetroCard();
        when(service.saveOrUpdateCard(card)).thenReturn(card);
        
        ResponseEntity<RetroCard> response = controller.saveOrUpdateCard("123", card);
        
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("123", card.getBoardId());
    }

    @Test
    public void testDeleteCard() {
        doNothing().when(service).deleteCard("card1");
        
        ResponseEntity<Void> response = controller.deleteCard("123", "card1");
        
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }
}
