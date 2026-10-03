package com.agilespace.backend.controller;

import com.agilespace.backend.domain.BrainstormingBoard;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.BrainstormingService;
import com.agilespace.backend.service.SquadAccessService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

public class BrainstormingControllerTest {

    @Mock
    private BrainstormingService service;

    @Mock
    private SquadAccessService squadAccessService;

    @InjectMocks
    private BrainstormingController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testListBoards_filtersBySquadAndChecksAccess() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(service.listBoards("DDWMISSI")).thenReturn(Arrays.asList(new BrainstormingBoard()));

        ResponseEntity<List<BrainstormingBoard>> response = controller.listBoards("DDWMISSI", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        verify(squadAccessService).requireSquadReadAccess("DDWMISSI", request);
    }

    @Test
    public void testListBoards_notMemberOfSquad_forbidden() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(squadAccessService).requireSquadReadAccess("outra-squad", request);

        assertThrows(ResponseStatusException.class, () -> controller.listBoards("outra-squad", request));
        verify(service, never()).listBoards(anyString());
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
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE)).thenReturn("ADMIN");
        doNothing().when(service).deleteBoard("123");

        ResponseEntity<Void> response = controller.deleteBoard("123", request);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(service, times(1)).deleteBoard("123");
    }
}
