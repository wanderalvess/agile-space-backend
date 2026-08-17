package com.agilespace.backend.service;

import com.agilespace.backend.domain.BrainstormingBoard;
import com.agilespace.backend.domain.BrainstormingIdea;
import com.agilespace.backend.domain.BrainstormingGroup;
import com.agilespace.backend.domain.BrainstormingParticipant;
import com.agilespace.backend.repository.BrainstormingBoardRepository;
import com.agilespace.backend.repository.BrainstormingIdeaRepository;
import com.agilespace.backend.repository.BrainstormingGroupRepository;
import com.agilespace.backend.repository.BrainstormingParticipantRepository;
import com.agilespace.backend.websocket.BrainstormingWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BrainstormingService {

    private final BrainstormingBoardRepository boardRepository;
    private final BrainstormingIdeaRepository ideaRepository;
    private final BrainstormingGroupRepository groupRepository;
    private final BrainstormingParticipantRepository participantRepository;
    private final BrainstormingWebSocketHandler webSocketHandler;

    // --- Boards Logic ---
    @Transactional(readOnly = true)
    public Optional<BrainstormingBoard> getBoard(String id) {
        return boardRepository.findById(id);
    }

    @Transactional
    public BrainstormingBoard saveOrUpdateBoard(BrainstormingBoard board) {
        if (board.getId() == null || board.getId().trim().isEmpty()) {
            board.setId(UUID.randomUUID().toString());
        }
        if (board.getCreatedAt() == null || board.getCreatedAt().trim().isEmpty()) {
            board.setCreatedAt(new java.util.Date().toString());
        }
        BrainstormingBoard saved = boardRepository.save(board);
        webSocketHandler.broadcastEvent(saved.getId(), "BOARD_UPDATED", saved);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<BrainstormingBoard> listBoards() {
        return boardRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public void deleteBoard(String id) {
        ideaRepository.deleteByBoardId(id);
        groupRepository.deleteByBoardId(id);
        participantRepository.deleteByBoardId(id);
        boardRepository.deleteById(id);
        webSocketHandler.broadcastEvent(id, "BOARD_DELETED", Map.of("boardId", id));
    }

    // --- Participants Logic ---
    @Transactional(readOnly = true)
    public List<BrainstormingParticipant> getParticipants(String boardId) {
        return participantRepository.findByBoardIdOrderByNicknameAsc(boardId);
    }

    @Transactional
    public BrainstormingParticipant joinBoard(BrainstormingParticipant participant) {
        String dbId = participant.getBoardId() + "_" + participant.getId();
        participant.setDbId(dbId);
        BrainstormingParticipant saved = participantRepository.save(participant);
        webSocketHandler.broadcastEvent(participant.getBoardId(), "PARTICIPANT_JOINED", saved);
        return saved;
    }

    @Transactional
    public void leaveBoard(String boardId, String userId) {
        participantRepository.deleteByBoardIdAndId(boardId, userId);
        webSocketHandler.broadcastEvent(boardId, "PARTICIPANT_LEFT", Map.of("userId", userId));
    }

    // --- Ideas Logic ---
    @Transactional(readOnly = true)
    public List<BrainstormingIdea> getIdeas(String boardId) {
        return ideaRepository.findByBoardId(boardId);
    }

    @Transactional
    public BrainstormingIdea saveOrUpdateIdea(BrainstormingIdea idea) {
        if (idea.getId() == null || idea.getId().trim().isEmpty()) {
            idea.setId(UUID.randomUUID().toString());
        }
        if (idea.getCreatedAt() == null || idea.getCreatedAt().trim().isEmpty()) {
            idea.setCreatedAt(new java.util.Date().toString());
        }
        BrainstormingIdea saved = ideaRepository.save(idea);
        webSocketHandler.broadcastEvent(idea.getBoardId(), "IDEA_SAVED", saved);
        return saved;
    }

    @Transactional
    public void deleteIdea(String boardId, String ideaId) {
        ideaRepository.deleteById(ideaId);
        webSocketHandler.broadcastEvent(boardId, "IDEA_DELETED", Map.of("ideaId", ideaId));
    }

    @Transactional
    public void deleteIdeaWithCascade(String boardId, String ideaId) {
        // Desconecta sub-ideias (parentId torna-se null) antes de deletar a ideia pai
        List<BrainstormingIdea> ideas = ideaRepository.findByBoardId(boardId);
        for (BrainstormingIdea idea : ideas) {
            if (ideaId.equals(idea.getParentId())) {
                idea.setParentId(null);
                ideaRepository.save(idea);
            }
        }
        ideaRepository.deleteById(ideaId);
        webSocketHandler.broadcastEvent(boardId, "IDEA_DELETED", Map.of("ideaId", ideaId));
    }

    // --- Groups Logic ---
    @Transactional(readOnly = true)
    public List<BrainstormingGroup> getGroups(String boardId) {
        return groupRepository.findByBoardIdOrderByOrderAsc(boardId);
    }

    @Transactional
    public BrainstormingGroup saveOrUpdateGroup(BrainstormingGroup group) {
        if (group.getId() == null || group.getId().trim().isEmpty()) {
            group.setId(UUID.randomUUID().toString());
        }
        if (group.getCreatedAt() == null || group.getCreatedAt().trim().isEmpty()) {
            group.setCreatedAt(new java.util.Date().toString());
        }
        BrainstormingGroup saved = groupRepository.save(group);
        webSocketHandler.broadcastEvent(group.getBoardId(), "GROUP_SAVED", saved);
        return saved;
    }

    @Transactional
    public void deleteGroup(String boardId, String groupId) {
        // Remove associação das ideias ao grupo antes de apagá-lo
        List<BrainstormingIdea> ideas = ideaRepository.findByBoardId(boardId);
        for (BrainstormingIdea idea : ideas) {
            if (groupId.equals(idea.getGroupId())) {
                idea.setGroupId(null);
                ideaRepository.save(idea);
            }
        }
        groupRepository.deleteById(groupId);
        webSocketHandler.broadcastEvent(boardId, "GROUP_DELETED", Map.of("groupId", groupId));
    }
}
