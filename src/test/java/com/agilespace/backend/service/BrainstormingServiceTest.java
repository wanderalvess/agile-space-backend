package com.agilespace.backend.service;

import com.agilespace.backend.domain.BrainstormingBoard;
import com.agilespace.backend.domain.BrainstormingGroup;
import com.agilespace.backend.domain.BrainstormingIdea;
import com.agilespace.backend.domain.BrainstormingParticipant;
import com.agilespace.backend.repository.BrainstormingBoardRepository;
import com.agilespace.backend.repository.BrainstormingGroupRepository;
import com.agilespace.backend.repository.BrainstormingIdeaRepository;
import com.agilespace.backend.repository.BrainstormingParticipantRepository;
import com.agilespace.backend.websocket.BrainstormingWebSocketHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BrainstormingService - Gestão do Quadro de Brainstorming, Grupos e Ideias")
class BrainstormingServiceTest {

    @Mock private BrainstormingBoardRepository boardRepository;
    @Mock private BrainstormingIdeaRepository ideaRepository;
    @Mock private BrainstormingGroupRepository groupRepository;
    @Mock private BrainstormingParticipantRepository participantRepository;
    @Mock private BrainstormingWebSocketHandler webSocketHandler;

    @InjectMocks
    private BrainstormingService service;

    @Nested
    @DisplayName("Gestão do Quadro")
    class BoardTests {

        @Test
        @DisplayName("Deve buscar quadro existente por ID")
        void shouldGetBoardById() {
            BrainstormingBoard board = BrainstormingBoard.builder().id("b1").title("Ideação Arquitetural").build();
            when(boardRepository.findById("b1")).thenReturn(Optional.of(board));

            Optional<BrainstormingBoard> result = service.getBoard("b1");

            assertTrue(result.isPresent());
            assertEquals("Ideação Arquitetural", result.get().getTitle());
        }

        @Test
        @DisplayName("Deve salvar quadro gerando UUID e disparar evento WebSocket")
        void shouldSaveBoardAndBroadcast() {
            BrainstormingBoard board = BrainstormingBoard.builder().title("Nova Sessão").build();
            when(boardRepository.save(any(BrainstormingBoard.class))).thenAnswer(i -> i.getArgument(0));

            BrainstormingBoard saved = service.saveOrUpdateBoard(board);

            assertNotNull(saved.getId());
            assertNotNull(saved.getCreatedAt());
            verify(boardRepository).save(board);
            verify(webSocketHandler).broadcastEvent(eq(saved.getId()), eq("BOARD_UPDATED"), eq(saved));
        }

        @Test
        @DisplayName("Deve excluir quadro cascateando exclusão de ideias, grupos e participantes")
        void shouldDeleteBoardAndCascade() {
            service.deleteBoard("b1");

            verify(ideaRepository).deleteByBoardId("b1");
            verify(groupRepository).deleteByBoardId("b1");
            verify(participantRepository).deleteByBoardId("b1");
            verify(boardRepository).deleteById("b1");
            verify(webSocketHandler).broadcastEvent(eq("b1"), eq("BOARD_DELETED"), any());
        }
    }

    @Nested
    @DisplayName("Gestão de Ideias e Agrupamentos")
    class IdeaAndGroupTests {

        @Test
        @DisplayName("Deve salvar ideia e transmitir evento IDEA_SAVED")
        void shouldSaveIdeaAndBroadcast() {
            BrainstormingIdea idea = BrainstormingIdea.builder().boardId("b1").content("Migrar para microserviços").build();
            when(ideaRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            BrainstormingIdea saved = service.saveOrUpdateIdea(idea);

            assertNotNull(saved.getId());
            verify(webSocketHandler).broadcastEvent(eq("b1"), eq("IDEA_SAVED"), eq(saved));
        }

        @Test
        @DisplayName("Deve excluir ideia desacoplando ideias filhas (desfazendo parentId)")
        void shouldDeleteIdeaWithCascadeDisassociation() {
            BrainstormingIdea child = BrainstormingIdea.builder().id("i2").parentId("i1").boardId("b1").build();
            when(ideaRepository.findByBoardId("b1")).thenReturn(Collections.singletonList(child));

            service.deleteIdeaWithCascade("b1", "i1");

            assertNull(child.getParentId(), "Ideia filha deve ter parentId removido para não ficar órfã");
            verify(ideaRepository).save(child);
            verify(ideaRepository).deleteById("i1");
            verify(webSocketHandler).broadcastEvent(eq("b1"), eq("IDEA_DELETED"), any());
        }

        @Test
        @DisplayName("Deve excluir grupo desvinculando ideias que pertenciam a ele")
        void shouldDeleteGroupAndUnbindIdeas() {
            BrainstormingIdea ideaInGroup = BrainstormingIdea.builder().id("i1").groupId("g1").boardId("b1").build();
            when(ideaRepository.findByBoardId("b1")).thenReturn(Collections.singletonList(ideaInGroup));

            service.deleteGroup("b1", "g1");

            assertNull(ideaInGroup.getGroupId(), "Ideia deve ter groupId desvinculado");
            verify(ideaRepository).save(ideaInGroup);
            verify(groupRepository).deleteById("g1");
            verify(webSocketHandler).broadcastEvent(eq("b1"), eq("GROUP_DELETED"), any());
        }
    }
}
