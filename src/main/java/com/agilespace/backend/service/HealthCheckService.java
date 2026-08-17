package com.agilespace.backend.service;

import com.agilespace.backend.domain.HealthCheckBoard;
import com.agilespace.backend.domain.HealthCheckParticipant;
import com.agilespace.backend.domain.HealthCheckVote;
import com.agilespace.backend.repository.HealthCheckBoardRepository;
import com.agilespace.backend.repository.HealthCheckParticipantRepository;
import com.agilespace.backend.repository.HealthCheckVoteRepository;
import com.agilespace.backend.websocket.HealthCheckWebSocketHandler;
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
public class HealthCheckService {

    private final HealthCheckBoardRepository boardRepository;
    private final HealthCheckParticipantRepository participantRepository;
    private final HealthCheckVoteRepository voteRepository;
    private final HealthCheckWebSocketHandler webSocketHandler;

    // --- Boards Logic ---
    @Transactional(readOnly = true)
    public Optional<HealthCheckBoard> getBoard(String id) {
        return boardRepository.findById(id);
    }

    @Transactional
    public HealthCheckBoard saveOrUpdateBoard(HealthCheckBoard board) {
        if (board.getId() == null || board.getId().trim().isEmpty()) {
            board.setId(UUID.randomUUID().toString());
        }
        if (board.getCreatedAt() == null || board.getCreatedAt().trim().isEmpty()) {
            board.setCreatedAt(new java.util.Date().toString());
        }
        HealthCheckBoard saved = boardRepository.save(board);
        webSocketHandler.broadcastEvent(saved.getId(), "BOARD_UPDATED", saved);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<HealthCheckBoard> listBoards() {
        return boardRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public void deleteBoard(String id) {
        voteRepository.deleteByBoardId(id);
        participantRepository.deleteByBoardId(id);
        boardRepository.deleteById(id);
        webSocketHandler.broadcastEvent(id, "BOARD_DELETED", Map.of("boardId", id));
    }

    // --- Participants Logic ---
    @Transactional(readOnly = true)
    public List<HealthCheckParticipant> getParticipants(String boardId) {
        return participantRepository.findByBoardIdOrderByNicknameAsc(boardId);
    }

    @Transactional
    public HealthCheckParticipant joinBoard(HealthCheckParticipant participant) {
        String dbId = participant.getBoardId() + "_" + participant.getId();
        participant.setDbId(dbId);
        HealthCheckParticipant saved = participantRepository.save(participant);
        webSocketHandler.broadcastEvent(participant.getBoardId(), "PARTICIPANT_JOINED", saved);
        return saved;
    }

    @Transactional
    public void leaveBoard(String boardId, String userId) {
        participantRepository.deleteByBoardIdAndId(boardId, userId);
        webSocketHandler.broadcastEvent(boardId, "PARTICIPANT_LEFT", Map.of("userId", userId));
    }

    // --- Votes Logic ---
    @Transactional(readOnly = true)
    public List<HealthCheckVote> getVotes(String boardId) {
        return voteRepository.findByBoardId(boardId);
    }

    @Transactional(readOnly = true)
    public List<HealthCheckVote> getUserVotes(String boardId, String participantId) {
        return voteRepository.findByBoardIdAndParticipantId(boardId, participantId);
    }

    @Transactional
    public HealthCheckVote saveVote(HealthCheckVote vote) {
        String id = vote.getBoardId() + "_" + vote.getParticipantId() + "_" + vote.getDimensionKey();
        vote.setId(id);
        HealthCheckVote saved = voteRepository.save(vote);
        webSocketHandler.broadcastEvent(vote.getBoardId(), "VOTE_SAVED", saved);
        return saved;
    }
}
