package com.agilespace.backend.controller;

import com.agilespace.backend.domain.HealthCheckBoard;
import com.agilespace.backend.domain.HealthCheckParticipant;
import com.agilespace.backend.domain.HealthCheckVote;
import com.agilespace.backend.service.HealthCheckService;
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

public class HealthCheckControllerTest {

    @Mock
    private HealthCheckService service;

    @Mock
    private SquadAccessService squadAccessService;

    @InjectMocks
    private HealthCheckController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testListBoards_filtersBySquadAndChecksAccess() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(service.listBoards("DDWMISSI")).thenReturn(Arrays.asList(new HealthCheckBoard()));

        ResponseEntity<List<HealthCheckBoard>> response = controller.listBoards("DDWMISSI", request);

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
    public void testGetBoard() {
        HealthCheckBoard board = new HealthCheckBoard();
        when(service.getBoard("hc-1")).thenReturn(Optional.of(board));
        
        ResponseEntity<HealthCheckBoard> response = controller.getBoard("hc-1");
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testSaveOrUpdateBoard() {
        HealthCheckBoard board = new HealthCheckBoard();
        when(service.saveOrUpdateBoard(board)).thenReturn(board);
        
        ResponseEntity<HealthCheckBoard> response = controller.saveOrUpdateBoard(board);
        
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
    }

    @Test
    public void testJoinBoard() {
        HealthCheckParticipant participant = new HealthCheckParticipant();
        when(service.joinBoard(participant)).thenReturn(participant);
        
        ResponseEntity<HealthCheckParticipant> response = controller.joinBoard("hc-1", participant);
        
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("hc-1", participant.getBoardId());
    }

    @Test
    public void testGetVotes() {
        when(service.getVotes("hc-1")).thenReturn(Arrays.asList(new HealthCheckVote()));
        
        ResponseEntity<List<HealthCheckVote>> response = controller.getVotes("hc-1", null);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
    }

    @Test
    public void testSaveVote() {
        HealthCheckVote vote = new HealthCheckVote();
        when(service.saveVote(vote)).thenReturn(vote);
        
        ResponseEntity<HealthCheckVote> response = controller.saveVote("hc-1", vote);
        
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("hc-1", vote.getBoardId());
    }
}
