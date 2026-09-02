package com.agilespace.backend.controller;

import com.agilespace.backend.domain.RetroBoard;
import com.agilespace.backend.domain.RetroCard;
import com.agilespace.backend.domain.RetroParticipant;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.RetroService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/retros")
@RequiredArgsConstructor
public class RetroController {

    private final RetroService retroService;

    /**
     * Só o próprio usuário (ou ADMIN) sai de um board removendo sua própria participação.
     * Antes disso qualquer usuário autenticado removia qualquer participante trocando o userId na URL.
     */
    private static void requireSelfOrAdmin(String userId, HttpServletRequest request) {
        String callerId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        String role = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
        if ("ADMIN".equalsIgnoreCase(role)) {
            return;
        }
        if (callerId == null || !callerId.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito ao próprio usuário.");
        }
    }

    /**
     * Só participantes do board (criador incluso) ou ADMIN apagam cards.
     * Antes disso qualquer usuário autenticado apagava cards de qualquer board.
     */
    private void requireBoardAccess(String boardId, HttpServletRequest request) {
        String role = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
        if ("ADMIN".equalsIgnoreCase(role)) {
            return;
        }
        String callerId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        boolean isCreator = callerId != null && retroService.getBoard(boardId)
                .map(RetroBoard::getCreatorId)
                .map(callerId::equals)
                .orElse(false);
        boolean isParticipant = callerId != null && retroService.getParticipants(boardId).stream()
                .anyMatch(p -> callerId.equals(p.getId()));
        if (!isCreator && !isParticipant) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a participantes deste board.");
        }
    }

    // --- Board Endpoints ---
    @GetMapping
    public ResponseEntity<List<RetroBoard>> listBoards(
            @RequestParam(value = "limit", required = false, defaultValue = "1000") int limit) {
        return ResponseEntity.ok(retroService.listBoards(limit));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RetroBoard> getBoard(@PathVariable("id") String id) {
        return retroService.getBoard(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<RetroBoard> saveOrUpdateBoard(@Valid @RequestBody RetroBoard board) {
        return ResponseEntity.status(HttpStatus.CREATED).body(retroService.saveOrUpdateBoard(board));
    }

    // --- Participants Endpoints ---
    @GetMapping("/{id}/participants")
    public ResponseEntity<List<RetroParticipant>> getParticipants(@PathVariable("id") String id) {
        return ResponseEntity.ok(retroService.getParticipants(id));
    }

    @PostMapping("/{id}/participants")
    public ResponseEntity<RetroParticipant> addOrUpdateParticipant(
            @PathVariable("id") String boardId,
            @Valid @RequestBody RetroParticipant participant) {
        participant.setBoardId(boardId);
        return ResponseEntity.status(HttpStatus.CREATED).body(retroService.addOrUpdateParticipant(participant));
    }

    @DeleteMapping("/{id}/participants/{userId}")
    public ResponseEntity<Void> removeParticipant(
            @PathVariable("id") String boardId,
            @PathVariable("userId") String userId,
            HttpServletRequest request) {
        requireSelfOrAdmin(userId, request);
        retroService.removeParticipant(boardId, userId);
        return ResponseEntity.noContent().build();
    }

    // --- Cards Endpoints ---
    @GetMapping("/{id}/cards")
    public ResponseEntity<List<RetroCard>> getCards(@PathVariable("id") String id) {
        return ResponseEntity.ok(retroService.getCards(id));
    }

    @PostMapping("/{id}/cards")
    public ResponseEntity<RetroCard> saveOrUpdateCard(
            @PathVariable("id") String boardId,
            @Valid @RequestBody RetroCard card) {
        card.setBoardId(boardId);
        return ResponseEntity.status(HttpStatus.CREATED).body(retroService.saveOrUpdateCard(card));
    }

    @DeleteMapping("/{id}/cards/{cardId}")
    public ResponseEntity<Void> deleteCard(
            @PathVariable("id") String boardId,
            @PathVariable("cardId") String cardId,
            HttpServletRequest request) {
        requireBoardAccess(boardId, request);
        retroService.deleteCard(boardId, cardId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/cards/import")
    public ResponseEntity<Void> importActions(
            @PathVariable("id") String boardId,
            @RequestBody List<RetroCard> cards) {
        retroService.importActions(boardId, cards);
        return ResponseEntity.ok().build();
    }
}
