package com.agilespace.backend.controller;

import com.agilespace.backend.domain.BrainstormingBoard;
import com.agilespace.backend.domain.BrainstormingIdea;
import com.agilespace.backend.domain.BrainstormingGroup;
import com.agilespace.backend.domain.BrainstormingParticipant;
import com.agilespace.backend.service.BrainstormingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/brainstormings")
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class BrainstormingController {

    private final BrainstormingService brainstormingService;

    // --- Boards ---
    @GetMapping("/{id}")
    public ResponseEntity<BrainstormingBoard> getBoard(@PathVariable("id") String id) {
        return brainstormingService.getBoard(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<BrainstormingBoard> saveOrUpdateBoard(@RequestBody BrainstormingBoard board) {
        return ResponseEntity.status(HttpStatus.CREATED).body(brainstormingService.saveOrUpdateBoard(board));
    }

    @GetMapping
    public ResponseEntity<List<BrainstormingBoard>> listBoards() {
        return ResponseEntity.ok(brainstormingService.listBoards());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBoard(@PathVariable("id") String id) {
        brainstormingService.deleteBoard(id);
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
            @RequestBody BrainstormingParticipant participant) {
        participant.setBoardId(boardId);
        return ResponseEntity.status(HttpStatus.CREATED).body(brainstormingService.joinBoard(participant));
    }

    @DeleteMapping("/{boardId}/participants/{userId}")
    public ResponseEntity<Void> leaveBoard(
            @PathVariable("boardId") String boardId,
            @PathVariable("userId") String userId) {
        brainstormingService.leaveBoard(boardId, userId);
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
            @RequestBody BrainstormingIdea idea) {
        idea.setBoardId(boardId);
        return ResponseEntity.status(HttpStatus.CREATED).body(brainstormingService.saveOrUpdateIdea(idea));
    }

    @DeleteMapping("/{boardId}/ideas/{ideaId}")
    public ResponseEntity<Void> deleteIdea(
            @PathVariable("boardId") String boardId,
            @PathVariable("ideaId") String ideaId) {
        brainstormingService.deleteIdeaWithCascade(boardId, ideaId);
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
            @RequestBody BrainstormingGroup group) {
        group.setBoardId(boardId);
        return ResponseEntity.status(HttpStatus.CREATED).body(brainstormingService.saveOrUpdateGroup(group));
    }

    @DeleteMapping("/{boardId}/groups/{groupId}")
    public ResponseEntity<Void> deleteGroup(
            @PathVariable("boardId") String boardId,
            @PathVariable("groupId") String groupId) {
        brainstormingService.deleteGroup(boardId, groupId);
        return ResponseEntity.noContent().build();
    }
}
