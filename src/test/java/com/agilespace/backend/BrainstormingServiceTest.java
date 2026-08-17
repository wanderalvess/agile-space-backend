package com.agilespace.backend;

import com.agilespace.backend.domain.BrainstormingBoard;
import com.agilespace.backend.domain.BrainstormingGroup;
import com.agilespace.backend.domain.BrainstormingIdea;
import com.agilespace.backend.domain.BrainstormingParticipant;
import com.agilespace.backend.repository.BrainstormingBoardRepository;
import com.agilespace.backend.repository.BrainstormingGroupRepository;
import com.agilespace.backend.repository.BrainstormingIdeaRepository;
import com.agilespace.backend.repository.BrainstormingParticipantRepository;
import com.agilespace.backend.service.BrainstormingService;
import com.agilespace.backend.websocket.BrainstormingWebSocketHandler;
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

public class BrainstormingServiceTest {

    @Mock
    private BrainstormingBoardRepository boardRepository;

    @Mock
    private BrainstormingIdeaRepository ideaRepository;

    @Mock
    private BrainstormingGroupRepository groupRepository;

    @Mock
    private BrainstormingParticipantRepository participantRepository;

    @Mock
    private BrainstormingWebSocketHandler webSocketHandler;

    @InjectMocks
    private BrainstormingService service;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testGetBoard() {
        BrainstormingBoard board = BrainstormingBoard.builder().id("b1").title("T").build();
        when(boardRepository.findById("b1")).thenReturn(Optional.of(board));
        Optional<BrainstormingBoard> result = service.getBoard("b1");
        assertTrue(result.isPresent());
        assertEquals("T", result.get().getTitle());
    }

    @Test
    public void testSaveBoardGeneratesIdAndBroadcasts() {
        BrainstormingBoard board = BrainstormingBoard.builder().title("New").build();
        when(boardRepository.save(any(BrainstormingBoard.class))).thenAnswer(i -> i.getArgument(0));

        BrainstormingBoard saved = service.saveOrUpdateBoard(board);

        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        verify(webSocketHandler).broadcastEvent(eq(saved.getId()), eq("BOARD_UPDATED"), any());
    }

    @Test
    public void testListBoards() {
        when(boardRepository.findAllByOrderByCreatedAtDesc()).thenReturn(Arrays.asList(new BrainstormingBoard()));
        List<BrainstormingBoard> list = service.listBoards();
        assertEquals(1, list.size());
    }

    @Test
    public void testDeleteBoard() {
        service.deleteBoard("b1");
        verify(ideaRepository).deleteByBoardId("b1");
        verify(groupRepository).deleteByBoardId("b1");
        verify(participantRepository).deleteByBoardId("b1");
        verify(boardRepository).deleteById("b1");
        verify(webSocketHandler).broadcastEvent(eq("b1"), eq("BOARD_DELETED"), any());
    }

    @Test
    public void testGetParticipants() {
        when(participantRepository.findByBoardIdOrderByNicknameAsc("b1")).thenReturn(Arrays.asList(new BrainstormingParticipant()));
        List<BrainstormingParticipant> list = service.getParticipants("b1");
        assertEquals(1, list.size());
    }

    @Test
    public void testJoinBoard() {
        BrainstormingParticipant p = BrainstormingParticipant.builder().boardId("b1").id("u1").build();
        when(participantRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        BrainstormingParticipant saved = service.joinBoard(p);

        assertEquals("b1_u1", saved.getDbId());
        verify(webSocketHandler).broadcastEvent(eq("b1"), eq("PARTICIPANT_JOINED"), any());
    }

    @Test
    public void testLeaveBoard() {
        service.leaveBoard("b1", "u1");
        verify(participantRepository).deleteByBoardIdAndId("b1", "u1");
        verify(webSocketHandler).broadcastEvent(eq("b1"), eq("PARTICIPANT_LEFT"), any());
    }

    @Test
    public void testGetIdeas() {
        when(ideaRepository.findByBoardId("b1")).thenReturn(Arrays.asList(new BrainstormingIdea()));
        List<BrainstormingIdea> list = service.getIdeas("b1");
        assertEquals(1, list.size());
    }

    @Test
    public void testSaveOrUpdateIdea() {
        BrainstormingIdea idea = BrainstormingIdea.builder().boardId("b1").build();
        when(ideaRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        BrainstormingIdea saved = service.saveOrUpdateIdea(idea);

        assertNotNull(saved.getId());
        verify(webSocketHandler).broadcastEvent(eq("b1"), eq("IDEA_SAVED"), any());
    }

    @Test
    public void testDeleteIdea() {
        service.deleteIdea("b1", "i1");
        verify(ideaRepository).deleteById("i1");
        verify(webSocketHandler).broadcastEvent(eq("b1"), eq("IDEA_DELETED"), any());
    }

    @Test
    public void testDeleteIdeaWithCascade() {
        BrainstormingIdea child = BrainstormingIdea.builder().id("i2").parentId("i1").boardId("b1").build();
        when(ideaRepository.findByBoardId("b1")).thenReturn(Arrays.asList(child));

        service.deleteIdeaWithCascade("b1", "i1");

        assertNull(child.getParentId());
        verify(ideaRepository).save(child);
        verify(ideaRepository).deleteById("i1");
        verify(webSocketHandler).broadcastEvent(eq("b1"), eq("IDEA_DELETED"), any());
    }

    @Test
    public void testGetGroups() {
        when(groupRepository.findByBoardIdOrderByOrderAsc("b1")).thenReturn(Arrays.asList(new BrainstormingGroup()));
        List<BrainstormingGroup> list = service.getGroups("b1");
        assertEquals(1, list.size());
    }

    @Test
    public void testSaveOrUpdateGroup() {
        BrainstormingGroup group = BrainstormingGroup.builder().boardId("b1").build();
        when(groupRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        BrainstormingGroup saved = service.saveOrUpdateGroup(group);

        assertNotNull(saved.getId());
        verify(webSocketHandler).broadcastEvent(eq("b1"), eq("GROUP_SAVED"), any());
    }

    @Test
    public void testDeleteGroup() {
        BrainstormingIdea ideaInGroup = BrainstormingIdea.builder().id("i1").groupId("g1").boardId("b1").build();
        when(ideaRepository.findByBoardId("b1")).thenReturn(Arrays.asList(ideaInGroup));

        service.deleteGroup("b1", "g1");

        assertNull(ideaInGroup.getGroupId());
        verify(ideaRepository).save(ideaInGroup);
        verify(groupRepository).deleteById("g1");
        verify(webSocketHandler).broadcastEvent(eq("b1"), eq("GROUP_DELETED"), any());
    }
}
