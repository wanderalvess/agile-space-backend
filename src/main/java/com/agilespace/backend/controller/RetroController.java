package com.agilespace.backend.controller;

import com.agilespace.backend.domain.RetroBoard;
import com.agilespace.backend.domain.RetroCard;
import com.agilespace.backend.domain.RetroChatMessage;
import com.agilespace.backend.domain.RetroParticipant;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.RetroCaller;
import com.agilespace.backend.service.RetroService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/retros")
@RequiredArgsConstructor
public class RetroController {

    private final RetroService retroService;
    private final com.agilespace.backend.service.SquadAccessService squadAccessService;

    /** Identidade do chamador, sempre a do JWT (nunca do corpo da requisição). */
    private static RetroCaller caller(HttpServletRequest request) {
        return new RetroCaller(
                (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID),
                (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE));
    }

    private static <T> ResponseEntity<T> upsert(RetroService.Saved<T> saved) {
        return ResponseEntity.status(saved.created() ? HttpStatus.CREATED : HttpStatus.OK).body(saved.value());
    }

    // --- Board Endpoints ---
    /**
     * squadId/team são obrigatórios (um dos dois) pra qualquer listagem "de squad" — antes
     * a ausência de ambos caía num fallback que devolvia boards de TODAS as squads, sem
     * checagem de pertencimento nenhuma. sprintId devolve só os boards que o chamador pode ler.
     */
    @GetMapping
    public ResponseEntity<List<RetroBoard>> listBoards(
            @RequestParam(value = "sprintId", required = false) String sprintId,
            @RequestParam(value = "team", required = false) String team,
            @RequestParam(value = "squadId", required = false) String squadId,
            HttpServletRequest request) {
        if (sprintId != null && !sprintId.isBlank()) {
            return ResponseEntity.ok(retroService.listBoardsBySprintId(sprintId, caller(request)));
        }
        if (squadId != null && !squadId.isBlank()) {
            squadAccessService.requireSquadReadAccess(squadId, request);
            return ResponseEntity.ok(retroService.listBoardsBySquadId(squadId));
        }
        if (team != null && !team.isBlank()) {
            squadAccessService.requireSquadReadAccess(team, request);
            return ResponseEntity.ok(retroService.listBoardsByTeam(team));
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe squadId, team ou sprintId.");
    }

    @GetMapping("/{id}")
    public ResponseEntity<RetroBoard> getBoard(@PathVariable("id") String id, HttpServletRequest request) {
        return retroService.getBoard(id, caller(request))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<RetroBoard> saveOrUpdateBoard(@Valid @RequestBody RetroBoard board, HttpServletRequest request) {
        return upsert(retroService.saveOrUpdateBoard(board, caller(request)));
    }

    /** Atualização parcial: só os campos presentes no JSON são alterados. */
    @PatchMapping("/{id}")
    public ResponseEntity<RetroBoard> patchBoard(
            @PathVariable("id") String id,
            @RequestBody JsonNode patch,
            HttpServletRequest request) {
        return ResponseEntity.ok(retroService.patchBoard(id, patch, caller(request)));
    }

    /** O chamador (participante) assume o controle do board; uma única flag isCreator. */
    @PostMapping("/{id}/transfer-control")
    public ResponseEntity<RetroBoard> transferControl(@PathVariable("id") String id, HttpServletRequest request) {
        return ResponseEntity.ok(retroService.transferControl(id, caller(request)));
    }

    /** Zera os votos de todos os cards e desativa a votação (criador/ADMIN). */
    @PostMapping("/{id}/votes/reset")
    public ResponseEntity<RetroBoard> resetVotes(@PathVariable("id") String id, HttpServletRequest request) {
        return ResponseEntity.ok(retroService.resetVotes(id, caller(request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBoard(@PathVariable("id") String id, HttpServletRequest request) {
        retroService.deleteBoard(id, caller(request));
        return ResponseEntity.noContent().build();
    }

    // --- Participants Endpoints ---
    @GetMapping("/{id}/participants")
    public ResponseEntity<List<RetroParticipant>> getParticipants(@PathVariable("id") String id, HttpServletRequest request) {
        return ResponseEntity.ok(retroService.getParticipants(id, caller(request)));
    }

    @PostMapping("/{id}/participants")
    public ResponseEntity<RetroParticipant> addOrUpdateParticipant(
            @PathVariable("id") String boardId,
            @Valid @RequestBody RetroParticipant participant,
            HttpServletRequest request) {
        participant.setBoardId(boardId);
        return upsert(retroService.addOrUpdateParticipant(participant, caller(request)));
    }

    @DeleteMapping("/{id}/participants/{userId}")
    public ResponseEntity<Void> removeParticipant(
            @PathVariable("id") String boardId,
            @PathVariable("userId") String userId,
            HttpServletRequest request) {
        retroService.removeParticipant(boardId, userId, caller(request));
        return ResponseEntity.noContent().build();
    }

    // --- Cards Endpoints ---
    @GetMapping("/{id}/cards")
    public ResponseEntity<List<RetroCard>> getCards(@PathVariable("id") String id, HttpServletRequest request) {
        return ResponseEntity.ok(retroService.getCards(id, caller(request)));
    }

    @PostMapping("/{id}/cards")
    public ResponseEntity<RetroCard> saveOrUpdateCard(
            @PathVariable("id") String boardId,
            @Valid @RequestBody RetroCard card,
            HttpServletRequest request) {
        card.setBoardId(boardId);
        return upsert(retroService.saveOrUpdateCard(card, caller(request)));
    }

    @DeleteMapping("/{id}/cards/{cardId}")
    public ResponseEntity<Void> deleteCard(
            @PathVariable("id") String boardId,
            @PathVariable("cardId") String cardId,
            HttpServletRequest request) {
        retroService.deleteCard(boardId, cardId, caller(request));
        return ResponseEntity.noContent().build();
    }

    /** Alterna o voto do chamador no card (userId do JWT). */
    @PostMapping("/{id}/cards/{cardId}/vote")
    public ResponseEntity<RetroCard> vote(
            @PathVariable("id") String boardId,
            @PathVariable("cardId") String cardId,
            HttpServletRequest request) {
        return ResponseEntity.ok(retroService.toggleVote(boardId, cardId, caller(request)));
    }

    /** Funde source em target numa transação. */
    @PostMapping("/{id}/cards/{targetId}/merge/{sourceId}")
    public ResponseEntity<RetroCard> mergeCards(
            @PathVariable("id") String boardId,
            @PathVariable("targetId") String targetId,
            @PathVariable("sourceId") String sourceId,
            HttpServletRequest request) {
        return ResponseEntity.ok(retroService.mergeCards(boardId, targetId, sourceId, caller(request)));
    }

    @PostMapping("/{id}/cards/import")
    public ResponseEntity<Void> importActions(
            @PathVariable("id") String boardId,
            @Valid @RequestBody List<@Valid RetroCard> cards,
            HttpServletRequest request) {
        retroService.importActions(boardId, cards, caller(request));
        return ResponseEntity.ok().build();
    }

    // --- Chat Endpoints ---
    @GetMapping("/{id}/chat")
    public ResponseEntity<List<RetroChatMessage>> getChatMessages(
            @PathVariable("id") String boardId,
            @RequestParam("channelId") String channelId,
            HttpServletRequest request) {
        String callerId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        return ResponseEntity.ok(retroService.getChatMessages(boardId, channelId, callerId));
    }

    @PostMapping("/{id}/chat")
    public ResponseEntity<RetroChatMessage> sendChatMessage(
            @PathVariable("id") String boardId,
            @RequestBody RetroChatMessage message,
            HttpServletRequest request) {
        String callerId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        return ResponseEntity.status(HttpStatus.CREATED).body(retroService.saveChatMessage(boardId, message, callerId));
    }

    @DeleteMapping("/{id}/chat/{messageId}")
    public ResponseEntity<Void> deleteChatMessage(
            @PathVariable("id") String boardId,
            @PathVariable("messageId") String messageId,
            HttpServletRequest request) {
        String callerId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        retroService.deleteChatMessage(boardId, messageId, callerId);
        return ResponseEntity.noContent().build();
    }

    // --- Erros ---

    /** Gravação concorrente do mesmo board/card (@Version): o cliente recarrega e tenta de novo. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "CONFLICT",
                "message", "O quadro foi atualizado por outra pessoa antes desta ação ser salva. Recarregue e tente novamente."));
    }

    /** Violação de limite/constraint do banco que escapou da validação: 400 em vez de 500. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrity(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "BAD_REQUEST",
                "message", "Dados inválidos ou fora dos limites permitidos."));
    }
}
