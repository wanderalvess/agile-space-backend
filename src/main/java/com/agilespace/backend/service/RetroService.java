package com.agilespace.backend.service;

import com.agilespace.backend.domain.RetroBoard;
import com.agilespace.backend.domain.RetroCard;
import com.agilespace.backend.domain.RetroParticipant;
import com.agilespace.backend.repository.RetroBoardRepository;
import com.agilespace.backend.repository.RetroCardRepository;
import com.agilespace.backend.repository.RetroParticipantRepository;
import com.agilespace.backend.websocket.RetroWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class RetroService {

    private final RetroBoardRepository boardRepository;
    private final RetroParticipantRepository participantRepository;
    private final RetroCardRepository cardRepository;
    private final RetroWebSocketHandler webSocketHandler;

    // --- Board Logic ---
    @Transactional(readOnly = true)
    public List<RetroBoard> listBoards() {
        return boardRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<RetroBoard> getBoard(String boardId) {
        return boardRepository.findById(boardId);
    }

    @Transactional
    public RetroBoard saveOrUpdateBoard(RetroBoard board) {
        RetroBoard saved = boardRepository.save(board);
        webSocketHandler.broadcastEvent(saved.getId(), "BOARD_UPDATED", saved);
        return saved;
    }

    // --- Participants Logic ---
    @Transactional(readOnly = true)
    public List<RetroParticipant> getParticipants(String boardId) {
        return participantRepository.findByBoardId(boardId);
    }

    @Transactional
    public RetroParticipant addOrUpdateParticipant(RetroParticipant participant) {
        // ID composto implícito para garantir unicidade na tabela e evitar conflitos de restrição
        String dbId = participant.getBoardId() + "_" + participant.getId();
        participant.setDbId(dbId);
        RetroParticipant saved = participantRepository.save(participant);
        webSocketHandler.broadcastEvent(participant.getBoardId(), "PARTICIPANT_JOINED", saved);
        return saved;
    }

    @Transactional
    public void removeParticipant(String boardId, String userId) {
        participantRepository.deleteByBoardIdAndId(boardId, userId);
        webSocketHandler.broadcastEvent(boardId, "PARTICIPANT_LEFT", Map.of("userId", userId));
    }

    // --- Cards Logic ---
    @Transactional(readOnly = true)
    public List<RetroCard> getCards(String boardId) {
        return cardRepository.findByBoardId(boardId);
    }

    @Transactional
    public RetroCard saveOrUpdateCard(RetroCard card) {
        RetroCard saved = cardRepository.save(card);
        webSocketHandler.broadcastEvent(card.getBoardId(), "CARD_SAVED", saved);
        return saved;
    }

    @Transactional
    public void deleteCard(String cardId) {
        Optional<RetroCard> cardOpt = cardRepository.findById(cardId);
        if (cardOpt.isPresent()) {
            RetroCard card = cardOpt.get();
            cardRepository.deleteById(cardId);
            webSocketHandler.broadcastEvent(card.getBoardId(), "CARD_DELETED", Map.of("cardId", cardId));
        }
    }

    @Transactional
    public void importActions(String boardId, List<RetroCard> cards) {
        for (RetroCard card : cards) {
            card.setBoardId(boardId);
            cardRepository.save(card);
        }
        webSocketHandler.broadcastEvent(boardId, "CARDS_IMPORTED", cards);
    }
}
