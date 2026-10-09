package com.agilespace.backend.controller;

import com.agilespace.backend.domain.HealthCheckBoard;
import com.agilespace.backend.domain.HealthCheckParticipant;
import com.agilespace.backend.domain.HealthCheckVote;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.CeremonyCaller;
import com.agilespace.backend.service.HealthCheckService;
import com.agilespace.backend.service.SquadAccessService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Radar de Saúde. Identidade sempre do JWT. Votos são anônimos: ver {@link HealthCheckService}.
 */
@RestController
@RequestMapping("/api/health-checks")
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class HealthCheckController {

    private final HealthCheckService healthCheckService;
    private final SquadAccessService squadAccessService;

    private static CeremonyCaller caller(HttpServletRequest request) {
        return new CeremonyCaller(
                (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID),
                (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE));
    }

    // --- Boards Endpoints ---
    @GetMapping("/{id}")
    public ResponseEntity<HealthCheckBoard> getBoard(@PathVariable("id") String id) {
        return healthCheckService.getBoard(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<HealthCheckBoard> saveOrUpdateBoard(@RequestBody HealthCheckBoard board, HttpServletRequest request) {
        HealthCheckService.Saved<HealthCheckBoard> saved = healthCheckService.saveOrUpdateBoard(board, caller(request));
        return ResponseEntity.status(saved.created() ? HttpStatus.CREATED : HttpStatus.OK).body(saved.value());
    }

    /** Encerra a coleta e calcula o resumo no servidor. Só o criador (ou ADMIN). */
    @PostMapping("/{id}/finish")
    public ResponseEntity<HealthCheckBoard> finish(@PathVariable("id") String id, HttpServletRequest request) {
        return ResponseEntity.ok(healthCheckService.finish(id, caller(request)));
    }

    @GetMapping
    public ResponseEntity<List<HealthCheckBoard>> listBoards(@RequestParam("squadId") String squadId, HttpServletRequest request) {
        squadAccessService.requireSquadReadAccess(squadId, request);
        return ResponseEntity.ok(healthCheckService.listBoards(squadId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBoard(@PathVariable("id") String id, HttpServletRequest request) {
        healthCheckService.deleteBoard(id, caller(request));
        return ResponseEntity.noContent().build();
    }

    // --- Participants Endpoints ---
    @GetMapping("/{boardId}/participants")
    public ResponseEntity<List<HealthCheckParticipant>> getParticipants(@PathVariable("boardId") String boardId) {
        return ResponseEntity.ok(healthCheckService.getParticipants(boardId));
    }

    @PostMapping("/{boardId}/participants")
    public ResponseEntity<HealthCheckParticipant> joinBoard(
            @PathVariable("boardId") String boardId,
            @RequestBody HealthCheckParticipant participant,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(healthCheckService.joinBoard(boardId, participant, caller(request)));
    }

    @DeleteMapping("/{boardId}/participants/{userId}")
    public ResponseEntity<Void> leaveBoard(
            @PathVariable("boardId") String boardId,
            @PathVariable("userId") String userId,
            HttpServletRequest request) {
        healthCheckService.leaveBoard(boardId, userId, caller(request));
        return ResponseEntity.noContent().build();
    }

    // --- Votes Endpoints ---
    /**
     * Coleta aberta: só os votos de quem chama (o parâmetro {@code participantId} é aceito por compatibilidade, mas
     * não amplia o resultado). Encerrado: todos os votos, sem identificar quem votou.
     */
    @GetMapping("/{boardId}/votes")
    public ResponseEntity<List<HealthCheckVote>> getVotes(
            @PathVariable("boardId") String boardId,
            @RequestParam(value = "participantId", required = false) String participantId,
            HttpServletRequest request) {
        return ResponseEntity.ok(healthCheckService.getVotes(boardId, caller(request)));
    }

    @PostMapping("/{boardId}/votes")
    public ResponseEntity<HealthCheckVote> saveVote(
            @PathVariable("boardId") String boardId,
            @RequestBody HealthCheckVote vote,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(healthCheckService.saveVote(boardId, vote, caller(request)));
    }

    /** Em produção o Spring omite a mensagem das respostas de erro; as dos fluxos (em português) precisam chegar à tela. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "error", ex.getStatusCode().toString(),
                "message", ex.getReason() == null ? "Não foi possível concluir a operação." : ex.getReason()));
    }
}
