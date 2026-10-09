package com.agilespace.backend.controller;

import com.agilespace.backend.domain.BrainstormingBoard;
import com.agilespace.backend.domain.BrainstormingGroup;
import com.agilespace.backend.domain.BrainstormingIdea;
import com.agilespace.backend.domain.BrainstormingParticipant;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.BrainstormingService;
import com.agilespace.backend.service.CeremonyCaller;
import com.agilespace.backend.service.SquadAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Brainstorming. Toda identidade vem do JWT. O link do mural dá acesso a quem está autenticado; as regras finas
 * (facilitador, autor da ideia) ficam em {@link BrainstormingService}.
 */
@RestController
@RequestMapping("/api/brainstormings")
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class BrainstormingController {

    private final BrainstormingService brainstormingService;
    private final SquadAccessService squadAccessService;

    private static CeremonyCaller caller(HttpServletRequest request) {
        return new CeremonyCaller(
                (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID),
                (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE));
    }

    private static <T> ResponseEntity<T> upsert(BrainstormingService.Saved<T> saved) {
        return ResponseEntity.status(saved.created() ? HttpStatus.CREATED : HttpStatus.OK).body(saved.value());
    }

    // --- Boards ---
    @GetMapping("/{id}")
    public ResponseEntity<BrainstormingBoard> getBoard(@PathVariable("id") String id) {
        return brainstormingService.getBoard(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<BrainstormingBoard> saveOrUpdateBoard(@RequestBody BrainstormingBoard board, HttpServletRequest request) {
        return upsert(brainstormingService.saveOrUpdateBoard(board, caller(request)));
    }

    /** Atualização parcial (fase, timer, configurações, título): só o facilitador. */
    @PatchMapping("/{id}")
    public ResponseEntity<BrainstormingBoard> patchBoard(
            @PathVariable("id") String id,
            @RequestBody JsonNode patch,
            HttpServletRequest request) {
        return ResponseEntity.ok(brainstormingService.patchBoard(id, patch, caller(request)));
    }

    @GetMapping
    public ResponseEntity<List<BrainstormingBoard>> listBoards(@RequestParam("squadId") String squadId, HttpServletRequest request) {
        squadAccessService.requireSquadReadAccess(squadId, request);
        return ResponseEntity.ok(brainstormingService.listBoards(squadId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBoard(@PathVariable("id") String id, HttpServletRequest request) {
        brainstormingService.deleteBoard(id, caller(request));
        return ResponseEntity.noContent().build();
    }

    // --- Participants ---
    @GetMapping("/{boardId}/participants")
    public ResponseEntity<List<BrainstormingParticipant>> getParticipants(@PathVariable("boardId") String boardId) {
        return ResponseEntity.ok(brainstormingService.getParticipants(boardId));
    }

    @PostMapping("/{boardId}/participants")
    public ResponseEntity<BrainstormingParticipant> joinBoard(
            @PathVariable("boardId") String boardId,
            @RequestBody BrainstormingParticipant participant,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(brainstormingService.joinBoard(boardId, participant, caller(request)));
    }

    /** Sai da sala: a própria pessoa, o facilitador ou ADMIN. */
    @DeleteMapping("/{boardId}/participants/{userId}")
    public ResponseEntity<Void> leaveBoard(
            @PathVariable("boardId") String boardId,
            @PathVariable("userId") String userId,
            HttpServletRequest request) {
        brainstormingService.leaveBoard(boardId, userId, caller(request));
        return ResponseEntity.noContent().build();
    }

    // --- Ideas ---
    @GetMapping("/{boardId}/ideas")
    public ResponseEntity<List<BrainstormingIdea>> getIdeas(@PathVariable("boardId") String boardId) {
        return ResponseEntity.ok(brainstormingService.getIdeas(boardId));
    }

    @PostMapping("/{boardId}/ideas")
    public ResponseEntity<BrainstormingIdea> saveOrUpdateIdea(
            @PathVariable("boardId") String boardId,
            @RequestBody BrainstormingIdea idea,
            HttpServletRequest request) {
        return upsert(brainstormingService.saveOrUpdateIdea(boardId, idea, caller(request)));
    }

    /** Edição parcial da ideia (texto, posição, grupo, ligação, qualificadores). Votos só pelo endpoint de voto. */
    @PatchMapping("/{boardId}/ideas/{ideaId}")
    public ResponseEntity<BrainstormingIdea> patchIdea(
            @PathVariable("boardId") String boardId,
            @PathVariable("ideaId") String ideaId,
            @RequestBody JsonNode patch,
            HttpServletRequest request) {
        return ResponseEntity.ok(brainstormingService.patchIdea(boardId, ideaId, patch, caller(request)));
    }

    /** Liga/desliga o voto de quem chama (atômico no servidor). */
    @PostMapping("/{boardId}/ideas/{ideaId}/vote")
    public ResponseEntity<BrainstormingIdea> toggleVote(
            @PathVariable("boardId") String boardId,
            @PathVariable("ideaId") String ideaId,
            HttpServletRequest request) {
        return ResponseEntity.ok(brainstormingService.toggleVote(boardId, ideaId, caller(request)));
    }

    /** Funde a ideia de origem na de destino (texto, votos e ligações) numa só operação. */
    @PostMapping("/{boardId}/ideas/{targetId}/merge/{sourceId}")
    public ResponseEntity<BrainstormingIdea> mergeIdeas(
            @PathVariable("boardId") String boardId,
            @PathVariable("targetId") String targetId,
            @PathVariable("sourceId") String sourceId,
            HttpServletRequest request) {
        return ResponseEntity.ok(brainstormingService.mergeIdeas(boardId, targetId, sourceId, caller(request)));
    }

    @DeleteMapping("/{boardId}/ideas/{ideaId}")
    public ResponseEntity<Void> deleteIdea(
            @PathVariable("boardId") String boardId,
            @PathVariable("ideaId") String ideaId,
            HttpServletRequest request) {
        brainstormingService.deleteIdea(boardId, ideaId, caller(request));
        return ResponseEntity.noContent().build();
    }

    // --- Groups ---
    @GetMapping("/{boardId}/groups")
    public ResponseEntity<List<BrainstormingGroup>> getGroups(@PathVariable("boardId") String boardId) {
        return ResponseEntity.ok(brainstormingService.getGroups(boardId));
    }

    @PostMapping("/{boardId}/groups")
    public ResponseEntity<BrainstormingGroup> saveOrUpdateGroup(
            @PathVariable("boardId") String boardId,
            @RequestBody BrainstormingGroup group,
            HttpServletRequest request) {
        return upsert(brainstormingService.saveOrUpdateGroup(boardId, group, caller(request)));
    }

    @DeleteMapping("/{boardId}/groups/{groupId}")
    public ResponseEntity<Void> deleteGroup(
            @PathVariable("boardId") String boardId,
            @PathVariable("groupId") String groupId,
            HttpServletRequest request) {
        brainstormingService.deleteGroup(boardId, groupId, caller(request));
        return ResponseEntity.noContent().build();
    }

    /** Em produção o Spring omite a mensagem das respostas de erro; as dos fluxos (em português) precisam chegar à tela. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "error", ex.getStatusCode().toString(),
                "message", ex.getReason() == null ? "Não foi possível concluir a operação." : ex.getReason()));
    }
}
