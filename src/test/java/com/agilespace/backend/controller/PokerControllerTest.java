package com.agilespace.backend.controller;

import com.agilespace.backend.domain.PokerRoom;
import com.agilespace.backend.domain.PokerParticipant;
import com.agilespace.backend.domain.PokerVote;
import com.agilespace.backend.service.PokerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class PokerControllerTest {

    @Mock
    private PokerService service;

    @InjectMocks
    private PokerController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
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
        when(service.saveVote(vote)).thenReturn(vote);
        
        ResponseEntity<PokerVote> response = controller.saveVote("room-1", vote);
        
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("room-1", vote.getRoomId());
    }

    @Test
    public void testClearVotes() {
        doNothing().when(service).clearVotes("room-1");
        
        ResponseEntity<Void> response = controller.clearVotes("room-1");
        
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }
}
