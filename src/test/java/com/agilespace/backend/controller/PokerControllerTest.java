package com.agilespace.backend.controller;

import com.agilespace.backend.domain.PokerRoom;
import com.agilespace.backend.domain.PokerParticipant;
import com.agilespace.backend.domain.PokerVote;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.PokerService;
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

public class PokerControllerTest {

    @Mock
    private PokerService service;

    @Mock
    private SquadAccessService squadAccessService;

    @InjectMocks
    private PokerController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testListRooms_filtersBySquadAndChecksAccess() {
        PokerRoom room = new PokerRoom();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(service.listRooms(1000, "DDWMISSI")).thenReturn(List.of(room));

        ResponseEntity<List<PokerRoom>> response = controller.listRooms(1000, "DDWMISSI", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        verify(squadAccessService).requireSquadReadAccess("DDWMISSI", request);
    }

    @Test
    public void testListRooms_notMemberOfSquad_forbidden() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(squadAccessService).requireSquadReadAccess("outra-squad", request);

        assertThrows(ResponseStatusException.class, () -> controller.listRooms(1000, "outra-squad", request));
        verify(service, never()).listRooms(anyInt(), anyString());
    }

    @Test
    public void testGetRoom() {
        PokerRoom room = new PokerRoom();
        when(service.getRoom("room-1")).thenReturn(Optional.of(room));
        
        ResponseEntity<PokerRoom> response = controller.getRoom("room-1");
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testJoinRoom() {
        PokerParticipant participant = new PokerParticipant();
        when(service.joinRoom(participant)).thenReturn(participant);
        
        ResponseEntity<PokerParticipant> response = controller.joinRoom("room-1", participant);
        
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("room-1", participant.getRoomId());
    }

    @Test
    public void testSaveVote() {
        PokerVote vote = new PokerVote();
        vote.setParticipantId("user-1");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("user-1");
        when(service.saveVote(vote, "user-1")).thenReturn(vote);

        ResponseEntity<PokerVote> response = controller.saveVote("room-1", vote, request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("room-1", vote.getRoomId());
    }

    @Test
    public void testClearVotes() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("user-1");
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE)).thenReturn("MEMBER");
        doNothing().when(service).clearVotes("room-1", "user-1", "MEMBER");

        ResponseEntity<Void> response = controller.clearVotes("room-1", request);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }
}
