package com.agilespace.backend.controller;

import com.agilespace.backend.domain.PokerRoom;
import com.agilespace.backend.domain.PokerParticipant;
import com.agilespace.backend.domain.PokerVote;
import com.agilespace.backend.domain.PokerRound;
import com.agilespace.backend.service.PokerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/poker")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class PokerController {

    private final PokerService pokerService;

    // --- Room Endpoints ---
    @GetMapping("/{id}")
    public ResponseEntity<PokerRoom> getRoom(@PathVariable("id") String roomId) {
        return pokerService.getRoom(roomId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<PokerRoom> saveOrUpdateRoom(@RequestBody PokerRoom room) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pokerService.saveOrUpdateRoom(room));
    }

    @GetMapping
    public ResponseEntity<List<PokerRoom>> listRooms() {
        return ResponseEntity.ok(pokerService.listRooms());
    }

    // --- Participants Endpoints ---
    @GetMapping("/{roomId}/participants")
    public ResponseEntity<List<PokerParticipant>> getParticipants(@PathVariable("roomId") String roomId) {
        return ResponseEntity.ok(pokerService.getParticipants(roomId));
    }

    @PostMapping("/{roomId}/participants")
    public ResponseEntity<PokerParticipant> joinRoom(
            @PathVariable("roomId") String roomId,
            @RequestBody PokerParticipant participant) {
        participant.setRoomId(roomId);
        return ResponseEntity.status(HttpStatus.CREATED).body(pokerService.joinRoom(participant));
    }

    @PostMapping("/{roomId}/heartbeat/{userId}")
    public ResponseEntity<Void> sendHeartbeat(
            @PathVariable("roomId") String roomId,
            @PathVariable("userId") String userId) {
        pokerService.updateHeartbeat(roomId, userId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{roomId}/participants/{userId}")
    public ResponseEntity<Void> leaveRoom(
            @PathVariable("roomId") String roomId,
            @PathVariable("userId") String userId) {
        pokerService.leaveRoom(roomId, userId);
        return ResponseEntity.noContent().build();
    }

    // --- Votes Endpoints ---
    @GetMapping("/{roomId}/votes")
    public ResponseEntity<List<PokerVote>> getVotes(@PathVariable("roomId") String roomId) {
        return ResponseEntity.ok(pokerService.getVotes(roomId));
    }

    @PostMapping("/{roomId}/votes")
    public ResponseEntity<PokerVote> saveVote(
            @PathVariable("roomId") String roomId,
            @RequestBody PokerVote vote) {
        vote.setRoomId(roomId);
        return ResponseEntity.status(HttpStatus.CREATED).body(pokerService.saveVote(vote));
    }

    @DeleteMapping("/{roomId}/votes/{userId}")
    public ResponseEntity<Void> removeVote(
            @PathVariable("roomId") String roomId,
            @PathVariable("userId") String userId) {
        pokerService.removeVote(roomId, userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{roomId}/votes")
    public ResponseEntity<Void> clearVotes(@PathVariable("roomId") String roomId) {
        pokerService.clearVotes(roomId);
        return ResponseEntity.noContent().build();
    }

    // --- Rounds (History) Endpoints ---
    @GetMapping("/{roomId}/rounds")
    public ResponseEntity<List<PokerRound>> getRounds(
            @PathVariable("roomId") String roomId,
            @RequestParam(value = "limit", required = false, defaultValue = "100") int limit) {
        return ResponseEntity.ok(pokerService.getRounds(roomId, limit));
    }

    @PostMapping("/{roomId}/rounds")
    public ResponseEntity<PokerRound> saveRound(
            @PathVariable("roomId") String roomId,
            @RequestBody PokerRound round) {
        round.setRoomId(roomId);
        return ResponseEntity.status(HttpStatus.CREATED).body(pokerService.saveRound(round));
    }

    @DeleteMapping("/{roomId}/rounds")
    public ResponseEntity<Void> clearRounds(@PathVariable("roomId") String roomId) {
        pokerService.clearRounds(roomId);
        return ResponseEntity.noContent().build();
    }

    // --- Reaction Endpoint (Transient Broadcast) ---
    @PostMapping("/{roomId}/reactions")
    public ResponseEntity<Void> sendReaction(
            @PathVariable("roomId") String roomId,
            @RequestBody String reactionPayload) {
        pokerService.sendReaction(roomId, reactionPayload);
        return ResponseEntity.ok().build();
    }
}
