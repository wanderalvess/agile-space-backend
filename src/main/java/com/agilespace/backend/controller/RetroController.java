package com.agilespace.backend.controller;

import com.agilespace.backend.domain.RetroBoard;
import com.agilespace.backend.domain.RetroCard;
import com.agilespace.backend.domain.RetroParticipant;
import com.agilespace.backend.service.RetroService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/retros")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class RetroController {

    private final RetroService retroService;

    // --- Board Endpoints ---
    @GetMapping
    public ResponseEntity<List<RetroBoard>> listBoards() {
        return ResponseEntity.ok(retroService.listBoards());
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
            @PathVariable("userId") String userId) {
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
            @PathVariable("cardId") String cardId) {
        retroService.deleteCard(cardId);
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
