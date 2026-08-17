package com.agilespace.backend;

import com.agilespace.backend.domain.PokerParticipant;
import com.agilespace.backend.domain.PokerRoom;
import com.agilespace.backend.domain.PokerRound;
import com.agilespace.backend.domain.PokerVote;
import com.agilespace.backend.repository.PokerParticipantRepository;
import com.agilespace.backend.repository.PokerRoomRepository;
import com.agilespace.backend.repository.PokerRoundRepository;
import com.agilespace.backend.repository.PokerVoteRepository;
import com.agilespace.backend.service.PokerService;
import com.agilespace.backend.websocket.PokerWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Pageable;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class PokerServiceTest {

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

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testGetRoom() {
        PokerRoom room = PokerRoom.builder().id("r1").build();
        when(roomRepository.findById("r1")).thenReturn(Optional.of(room));
        Optional<PokerRoom> result = service.getRoom("r1");
        assertTrue(result.isPresent());
    }

    @Test
    public void testSaveOrUpdateRoomTriggersBroadcast() {
        PokerRoom room = PokerRoom.builder().id("room-123").title("Planning Poker Sprint 1").build();
        when(roomRepository.save(room)).thenReturn(room);

        PokerRoom saved = service.saveOrUpdateRoom(room);

        assertEquals("Planning Poker Sprint 1", saved.getTitle());
        verify(webSocketHandler, times(1)).broadcastEvent(eq("room-123"), eq("ROOM_UPDATED"), any());
        verify(roomRepository, times(1)).save(room);
    }

    @Test
    public void testListRooms() {
        when(roomRepository.findAll()).thenReturn(Arrays.asList(new PokerRoom()));
        List<PokerRoom> result = service.listRooms();
        assertEquals(1, result.size());
    }

    @Test
    public void testGetParticipants() {
        when(participantRepository.findByRoomIdOrderByNicknameAsc("r1")).thenReturn(Arrays.asList(new PokerParticipant()));
        List<PokerParticipant> result = service.getParticipants("r1");
        assertEquals(1, result.size());
    }

    @Test
    public void testJoinRoomGeneratesDbId() {
        PokerParticipant participant = PokerParticipant.builder()
                .roomId("room-123")
                .id("user-456")
                .nickname("Wanderson")
                .build();

        when(participantRepository.save(any(PokerParticipant.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PokerParticipant saved = service.joinRoom(participant);

        assertEquals("room-123_user-456", saved.getDbId());
        verify(webSocketHandler, times(1)).broadcastEvent(eq("room-123"), eq("PARTICIPANT_JOINED"), any());
    }

    @Test
    public void testUpdateHeartbeat() {
        PokerParticipant p = PokerParticipant.builder().dbId("r1_u1").build();
        when(participantRepository.findById("r1_u1")).thenReturn(Optional.of(p));
        when(participantRepository.save(any())).thenReturn(p);

        service.updateHeartbeat("r1", "u1");

        assertNotNull(p.getLastSeen());
        verify(participantRepository).save(p);
    }

    @Test
    public void testLeaveRoom() {
        service.leaveRoom("r1", "u1");
        verify(participantRepository).deleteByRoomIdAndId("r1", "u1");
        verify(webSocketHandler).broadcastEvent(eq("r1"), eq("PARTICIPANT_LEFT"), any());
    }

    @Test
    public void testGetVotes() {
        when(voteRepository.findByRoomId("r1")).thenReturn(Arrays.asList(new PokerVote()));
        List<PokerVote> result = service.getVotes("r1");
        assertEquals(1, result.size());
    }

    @Test
    public void testSaveVoteGeneratesCompositeId() {
        PokerVote vote = PokerVote.builder()
                .roomId("room-123")
                .participantId("user-456")
                .value("5")
                .build();

        when(voteRepository.save(any(PokerVote.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PokerVote saved = service.saveVote(vote);

        assertEquals("room-123_user-456", saved.getId());
        verify(webSocketHandler, times(1)).broadcastEvent(eq("room-123"), eq("VOTE_SAVED"), any());
    }

    @Test
    public void testRemoveVote() {
        service.removeVote("r1", "u1");
        verify(voteRepository).deleteByRoomIdAndParticipantId("r1", "u1");
        verify(webSocketHandler).broadcastEvent(eq("r1"), eq("VOTE_REMOVED"), any());
    }

    @Test
    public void testClearVotes() {
        service.clearVotes("r1");
        verify(voteRepository).deleteByRoomId("r1");
        verify(webSocketHandler).broadcastEvent(eq("r1"), eq("VOTES_CLEARED"), any());
    }

    @Test
    public void testGetRoundsWithLimit() {
        when(roundRepository.findRecentRounds(eq("r1"), any(Pageable.class))).thenReturn(Arrays.asList(new PokerRound()));
        List<PokerRound> result = service.getRounds("r1", 5);
        assertEquals(1, result.size());
    }

    @Test
    public void testGetRoundsNoLimit() {
        when(roundRepository.findByRoomId("r1")).thenReturn(Arrays.asList(new PokerRound(), new PokerRound()));
        List<PokerRound> result = service.getRounds("r1", 0);
        assertEquals(2, result.size());
    }

    @Test
    public void testSaveRound() {
        PokerRound round = PokerRound.builder().roomId("r1").build();
        when(roundRepository.save(any(PokerRound.class))).thenAnswer(i -> i.getArgument(0));

        PokerRound saved = service.saveRound(round);

        assertNotNull(saved.getId());
        verify(webSocketHandler).broadcastEvent(eq("r1"), eq("ROUND_SAVED"), any());
    }

    @Test
    public void testClearRounds() {
        service.clearRounds("r1");
        verify(roundRepository).deleteByRoomId("r1");
        verify(webSocketHandler).broadcastEvent(eq("r1"), eq("ROUNDS_CLEARED"), any());
    }

    @Test
    public void testSendReaction() {
        service.sendReaction("r1", "thumbsup");
        verify(webSocketHandler).broadcastReaction("r1", "thumbsup");
    }
}
