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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
    public List<RetroBoard> listBoards(int limit) {
        int safeLimit = limit > 0 ? limit : 1000;
        return boardRepository.findAll(PageRequest.of(0, safeLimit)).getContent();
    }

    @Transactional(readOnly = true)
    public List<RetroBoard> listBoardsBySprintId(String sprintId) {
        return boardRepository.findBySprintIdOrderByCreatedAtDesc(sprintId);
    }

    @Transactional(readOnly = true)
    public List<RetroBoard> listBoardsByTeam(String team) {
        return boardRepository.findByTeamOrderByCreatedAtDesc(team);
    }

    @Transactional(readOnly = true)
    public List<RetroBoard> listBoardsBySquadId(String squadId) {
        return boardRepository.findBySquadIdOrderByCreatedAtDesc(squadId);
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

    // Sem @Transactional de propósito: cada chamada a save()/findById() abaixo
    // já abre sua própria transação via Spring Data. Isso é o que permite o
    // catch abaixo tentar de novo depois de uma falha — dentro de UMA
    // transação compartilhada, uma DataIntegrityViolationException marca a
    // transação como rollback-only e qualquer save() seguinte no mesmo método
    // falharia ao tentar comitar, mesmo capturando a exceção.
    public RetroParticipant addOrUpdateParticipant(RetroParticipant incoming) {
        // ID composto implícito para garantir unicidade na tabela e evitar conflitos de restrição
        String dbId = incoming.getBoardId() + "_" + incoming.getId();
        incoming.setDbId(dbId);

        Optional<RetroParticipant> existing = participantRepository.findById(dbId);
        if (existing.isPresent()) {
            // Merge por campo, não replace total: o front dispara vários
            // "addOrUpdateParticipant" quase simultâneos pro mesmo participante
            // (auto-join da identidade global, sincronização de perfil, resposta
            // do check-in inicial) e cada um só sabe sobre o pedaço que está
            // mudando. Um save() do objeto inteiro faria o que chegasse por
            // último apagar campo que outro acabou de gravar (ex.: auto-join
            // sem healthCheckAnswer zerando a resposta que acabou de ser salva).
            RetroParticipant saved = participantRepository.save(mergeParticipant(existing.get(), incoming));
            webSocketHandler.broadcastEvent(incoming.getBoardId(), "PARTICIPANT_JOINED", saved);
            return saved;
        }

        RetroParticipant saved;
        try {
            saved = participantRepository.save(incoming);
        } catch (DataIntegrityViolationException e) {
            // Corrida na criação: duas requisições viram "não existe ainda" no
            // findById acima e tentam INSERT ao mesmo tempo — a segunda esbarra
            // na PK que a primeira acabou de commitar. Refaz como merge sobre a
            // linha que já existe agora em vez de propagar 500 pro cliente.
            saved = participantRepository.findById(dbId)
                    .map(row -> participantRepository.save(mergeParticipant(row, incoming)))
                    .orElseThrow(() -> e);
        }
        webSocketHandler.broadcastEvent(incoming.getBoardId(), "PARTICIPANT_JOINED", saved);
        return saved;
    }

    private RetroParticipant mergeParticipant(RetroParticipant existing, RetroParticipant incoming) {
        if (incoming.getNickname() != null) existing.setNickname(incoming.getNickname());
        if (incoming.getRole() != null) existing.setRole(incoming.getRole());
        if (incoming.getIsCreator() != null) existing.setIsCreator(incoming.getIsCreator());
        if (incoming.getGlobalRole() != null) existing.setGlobalRole(incoming.getGlobalRole());
        if (incoming.getHealthCheckAnswer() != null) existing.setHealthCheckAnswer(incoming.getHealthCheckAnswer());
        return existing;
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
        if (card.getId() != null) {
            cardRepository.findById(card.getId()).ifPresent(existing -> {
                if (!existing.getBoardId().equals(card.getBoardId())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                            "Cartão pertence a outro board.");
                }
            });
        }
        RetroCard saved = cardRepository.save(card);
        webSocketHandler.broadcastEvent(card.getBoardId(), "CARD_SAVED", saved);
        return saved;
    }

    @Transactional
    public void deleteCard(String boardId, String cardId) {
        Optional<RetroCard> cardOpt = cardRepository.findById(cardId);
        if (cardOpt.isPresent()) {
            RetroCard card = cardOpt.get();
            if (!card.getBoardId().equals(boardId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Cartão não pertence a este board.");
            }
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
