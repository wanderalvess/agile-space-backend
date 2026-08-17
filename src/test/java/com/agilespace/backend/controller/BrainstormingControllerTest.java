package com.agilespace.backend.controller;

import com.agilespace.backend.domain.BrainstormingBoard;
import com.agilespace.backend.service.BrainstormingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class BrainstormingControllerTest {

    @Mock
    private BrainstormingService service;

    @InjectMocks
    private BrainstormingController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testGetBoardFound() {
        BrainstormingBoard board = new BrainstormingBoard();
        when(service.getBoard("123")).thenReturn(Optional.of(board));
        
        ResponseEntity<BrainstormingBoard> response = controller.getBoard("123");
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testGetBoardNotFound() {
        when(service.getBoard("123")).thenReturn(Optional.empty());
        
        ResponseEntity<BrainstormingBoard> response = controller.getBoard("123");
        
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    public void testSaveOrUpdateBoard() {
        BrainstormingBoard board = new BrainstormingBoard();
        when(service.saveOrUpdateBoard(board)).thenReturn(board);
        
        ResponseEntity<BrainstormingBoard> response = controller.saveOrUpdateBoard(board);
        
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
    }

    @Test
    public void testDeleteBoard() {
        doNothing().when(service).deleteBoard("123");
        
        ResponseEntity<Void> response = controller.deleteBoard("123");
        
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(service, times(1)).deleteBoard("123");
    }
}
