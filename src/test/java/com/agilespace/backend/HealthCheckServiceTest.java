package com.agilespace.backend;

import com.agilespace.backend.domain.HealthCheckBoard;
import com.agilespace.backend.domain.HealthCheckParticipant;
import com.agilespace.backend.domain.HealthCheckVote;
import com.agilespace.backend.repository.HealthCheckBoardRepository;
import com.agilespace.backend.repository.HealthCheckParticipantRepository;
import com.agilespace.backend.repository.HealthCheckVoteRepository;
import com.agilespace.backend.service.HealthCheckService;
import com.agilespace.backend.websocket.HealthCheckWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class HealthCheckServiceTest {

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

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testGetBoard() {
        HealthCheckBoard board = HealthCheckBoard.builder().id("hc1").status("active").build();
        when(boardRepository.findById("hc1")).thenReturn(Optional.of(board));
        Optional<HealthCheckBoard> result = service.getBoard("hc1");
        assertTrue(result.isPresent());
        assertEquals("active", result.get().getStatus());
    }

    @Test
    public void testSaveBoardGeneratesIdAndBroadcasting() {
        HealthCheckBoard board = HealthCheckBoard.builder().status("planning").build();
        when(boardRepository.save(any(HealthCheckBoard.class))).thenAnswer(i -> i.getArgument(0));

        HealthCheckBoard saved = service.saveOrUpdateBoard(board);

        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        verify(webSocketHandler).broadcastEvent(eq(saved.getId()), eq("BOARD_UPDATED"), any());
        verify(boardRepository).save(board);
    }

    @Test
    public void testListBoards() {
        when(boardRepository.findAllByOrderByCreatedAtDesc()).thenReturn(Arrays.asList(new HealthCheckBoard()));
        List<HealthCheckBoard> list = service.listBoards();
        assertEquals(1, list.size());
    }

    @Test
    public void testDeleteBoard() {
        service.deleteBoard("hc1");
        verify(voteRepository).deleteByBoardId("hc1");
        verify(participantRepository).deleteByBoardId("hc1");
        verify(boardRepository).deleteById("hc1");
        verify(webSocketHandler).broadcastEvent(eq("hc1"), eq("BOARD_DELETED"), any());
    }

    @Test
    public void testGetParticipants() {
        when(participantRepository.findByBoardIdOrderByNicknameAsc("hc1")).thenReturn(Arrays.asList(new HealthCheckParticipant()));
        List<HealthCheckParticipant> list = service.getParticipants("hc1");
        assertEquals(1, list.size());
    }

    @Test
    public void testJoinBoard() {
        HealthCheckParticipant p = HealthCheckParticipant.builder().boardId("hc1").id("u1").build();
        when(participantRepository.save(any(HealthCheckParticipant.class))).thenAnswer(i -> i.getArgument(0));

        HealthCheckParticipant saved = service.joinBoard(p);

        assertEquals("hc1_u1", saved.getDbId());
        verify(participantRepository).save(p);
        verify(webSocketHandler).broadcastEvent(eq("hc1"), eq("PARTICIPANT_JOINED"), any());
    }

    @Test
    public void testLeaveBoard() {
        service.leaveBoard("hc1", "u1");
        verify(participantRepository).deleteByBoardIdAndId("hc1", "u1");
        verify(webSocketHandler).broadcastEvent(eq("hc1"), eq("PARTICIPANT_LEFT"), any());
    }

    @Test
    public void testGetVotes() {
        when(voteRepository.findByBoardId("hc1")).thenReturn(Arrays.asList(new HealthCheckVote()));
        List<HealthCheckVote> list = service.getVotes("hc1");
        assertEquals(1, list.size());
    }

    @Test
    public void testGetUserVotes() {
        when(voteRepository.findByBoardIdAndParticipantId("hc1", "u1")).thenReturn(Arrays.asList(new HealthCheckVote()));
        List<HealthCheckVote> list = service.getUserVotes("hc1", "u1");
        assertEquals(1, list.size());
    }

    @Test
    public void testSaveVote() {
        HealthCheckVote vote = HealthCheckVote.builder().boardId("hc1").participantId("u1").dimensionKey("speed").build();
        when(voteRepository.save(any(HealthCheckVote.class))).thenAnswer(i -> i.getArgument(0));

        HealthCheckVote saved = service.saveVote(vote);

        assertEquals("hc1_u1_speed", saved.getId());
        verify(voteRepository).save(vote);
        verify(webSocketHandler).broadcastEvent(eq("hc1"), eq("VOTE_SAVED"), any());
    }
}
