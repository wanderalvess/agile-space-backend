package com.agilespace.backend.controller;

import com.agilespace.backend.domain.HealthCheckBoard;
import com.agilespace.backend.domain.HealthCheckParticipant;
import com.agilespace.backend.domain.HealthCheckVote;
import com.agilespace.backend.service.HealthCheckService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/health-checks")
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class HealthCheckController {

    private final HealthCheckService healthCheckService;

    // --- Boards Endpoints ---
    @GetMapping("/{id}")
    public ResponseEntity<HealthCheckBoard> getBoard(@PathVariable("id") String id) {
        return healthCheckService.getBoard(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<HealthCheckBoard> saveOrUpdateBoard(@RequestBody HealthCheckBoard board) {
        return ResponseEntity.status(HttpStatus.CREATED).body(healthCheckService.saveOrUpdateBoard(board));
    }

    @GetMapping
    public ResponseEntity<List<HealthCheckBoard>> listBoards() {
        return ResponseEntity.ok(healthCheckService.listBoards());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBoard(@PathVariable("id") String id) {
        healthCheckService.deleteBoard(id);
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
            @RequestBody HealthCheckParticipant participant) {
        participant.setBoardId(boardId);
        return ResponseEntity.status(HttpStatus.CREATED).body(healthCheckService.joinBoard(participant));
    }

    @DeleteMapping("/{boardId}/participants/{userId}")
    public ResponseEntity<Void> leaveBoard(
            @PathVariable("boardId") String boardId,
            @PathVariable("userId") String userId) {
        healthCheckService.leaveBoard(boardId, userId);
        return ResponseEntity.noContent().build();
    }

    // --- Votes Endpoints ---
    @GetMapping("/{boardId}/votes")
    public ResponseEntity<List<HealthCheckVote>> getVotes(
            @PathVariable("boardId") String boardId,
            @RequestParam(value = "participantId", required = false) String participantId) {
        if (participantId != null && !participantId.trim().isEmpty()) {
            return ResponseEntity.ok(healthCheckService.getUserVotes(boardId, participantId));
        }
        return ResponseEntity.ok(healthCheckService.getVotes(boardId));
    }

    @PostMapping("/{boardId}/votes")
    public ResponseEntity<HealthCheckVote> saveVote(
            @PathVariable("boardId") String boardId,
            @RequestBody HealthCheckVote vote) {
        vote.setBoardId(boardId);
        return ResponseEntity.status(HttpStatus.CREATED).body(healthCheckService.saveVote(vote));
    }
}
