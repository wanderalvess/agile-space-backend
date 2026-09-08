package com.agilespace.backend.service;

import com.agilespace.backend.domain.PokerRoom;
import com.agilespace.backend.domain.PokerParticipant;
import com.agilespace.backend.domain.PokerVote;
import com.agilespace.backend.domain.PokerRound;
import com.agilespace.backend.repository.PokerRoomRepository;
import com.agilespace.backend.repository.PokerParticipantRepository;
import com.agilespace.backend.repository.PokerVoteRepository;
import com.agilespace.backend.repository.PokerRoundRepository;
import com.agilespace.backend.websocket.PokerWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
public class PokerService {

    private final PokerRoomRepository roomRepository;
    private final PokerParticipantRepository participantRepository;
    private final PokerVoteRepository voteRepository;
    private final PokerRoundRepository roundRepository;
    private final PokerWebSocketHandler webSocketHandler;

    // --- Room Logic ---
    @Transactional(readOnly = true)
    public Optional<PokerRoom> getRoom(String roomId) {
        return roomRepository.findById(roomId);
    }

    @Transactional
    public PokerRoom saveOrUpdateRoom(PokerRoom room, String callerId, String callerRole) {
        Optional<PokerRoom> existing = room.getId() != null ? roomRepository.findById(room.getId()) : Optional.empty();
        if (existing.isPresent()) {
            requireRoomParticipant(existing.get(), callerId, callerRole);
        } else {
            room.setCreatorId(callerId);
        }
        PokerRoom saved = roomRepository.save(room);
        webSocketHandler.broadcastEvent(saved.getId(), "ROOM_UPDATED", saved);
        return saved;
    }

    private boolean isPrivilegedRole(String role) {
        return "ADMIN".equalsIgnoreCase(role) || "LEAD".equalsIgnoreCase(role);
    }

    /**
     * Facilitador é transferível (ex.: host cai e outro participante assume), então o gate
     * aqui é "já entrou na sala" (creatorId, participante com join registrado, ou ADMIN/LEAD) —
     * não "só o criador original". Isso fecha o buraco real (outsider que nunca entrou na sala
     * mexendo via roomId adivinhado) sem travar o claim-facilitator do frontend.
     */
    private void requireRoomParticipant(PokerRoom room, String callerId, String callerRole) {
        if (isPrivilegedRole(callerRole)) {
            return;
        }
        if (callerId != null && callerId.equals(room.getCreatorId())) {
            return;
        }
        if (callerId != null && participantRepository.existsById(room.getId() + "_" + callerId)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Apenas participantes da sala podem executar esta ação.");
    }

    @Transactional(readOnly = true)
    public List<PokerRoom> listRooms(int limit) {
        int safeLimit = limit > 0 ? limit : 1000;
        return roomRepository.findAll(PageRequest.of(0, safeLimit)).getContent();
    }

    // --- Participants Logic ---
    @Transactional(readOnly = true)
    public List<PokerParticipant> getParticipants(String roomId) {
        return participantRepository.findByRoomIdOrderByNicknameAsc(roomId);
    }

    @Transactional
    public PokerParticipant joinRoom(PokerParticipant participant) {
        String dbId = participant.getRoomId() + "_" + participant.getId();
        participant.setDbId(dbId);
        PokerParticipant saved = participantRepository.save(participant);
        webSocketHandler.broadcastEvent(participant.getRoomId(), "PARTICIPANT_JOINED", saved);
        return saved;
    }

    @Transactional
    public void updateHeartbeat(String roomId, String userId) {
        String dbId = roomId + "_" + userId;
        participantRepository.findById(dbId).ifPresent(p -> {
            p.setLastSeen(new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(new java.util.Date()));
            participantRepository.save(p);
        });
    }

    @Transactional
    public void leaveRoom(String roomId, String userId) {
        participantRepository.deleteByRoomIdAndId(roomId, userId);
        webSocketHandler.broadcastEvent(roomId, "PARTICIPANT_LEFT", Map.of("userId", userId));
    }

    // --- Votes Logic ---
    @Transactional(readOnly = true)
    public List<PokerVote> getVotes(String roomId) {
        return voteRepository.findByRoomId(roomId);
    }

    @Transactional
    public PokerVote saveVote(PokerVote vote, String callerId) {
        if (callerId == null || !callerId.equals(vote.getParticipantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Só é possível registrar o próprio voto.");
        }
        String id = vote.getRoomId() + "_" + vote.getParticipantId();
        vote.setId(id);
        PokerVote saved = voteRepository.save(vote);
        webSocketHandler.broadcastEvent(vote.getRoomId(), "VOTE_SAVED", saved);
        return saved;
    }

    @Transactional
    public void removeVote(String roomId, String userId) {
        voteRepository.deleteByRoomIdAndParticipantId(roomId, userId);
        webSocketHandler.broadcastEvent(roomId, "VOTE_REMOVED", Map.of("userId", userId));
    }

    @Transactional
    public void clearVotes(String roomId, String callerId, String callerRole) {
        PokerRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sala não encontrada"));
        requireRoomParticipant(room, callerId, callerRole);
        voteRepository.deleteByRoomId(roomId);
        webSocketHandler.broadcastEvent(roomId, "VOTES_CLEARED", Map.of());
    }

    // --- Rounds (History) Logic ---
    @Transactional(readOnly = true)
    public List<PokerRound> getRounds(String roomId, int limit) {
        if (limit > 0) {
            return roundRepository.findRecentRounds(roomId, PageRequest.of(0, limit));
        }
        return roundRepository.findByRoomId(roomId);
    }

    @Transactional
    public PokerRound saveRound(PokerRound round) {
        if (round.getId() == null || round.getId().trim().isEmpty()) {
            round.setId(java.util.UUID.randomUUID().toString());
        }
        PokerRound saved = roundRepository.save(round);
        webSocketHandler.broadcastEvent(round.getRoomId(), "ROUND_SAVED", saved);
        return saved;
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<PokerRound> searchRounds(String query, org.springframework.data.domain.Pageable pageable) {
        return roundRepository.searchByTopicOrNote(query, pageable);
    }

    @Transactional
    public void clearRounds(String roomId, String callerId, String callerRole) {
        PokerRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sala não encontrada"));
        requireRoomParticipant(room, callerId, callerRole);
        roundRepository.deleteByRoomId(roomId);
        webSocketHandler.broadcastEvent(roomId, "ROUNDS_CLEARED", Map.of());
    }

    // --- Reaction Logic (WebSocket Only) ---
    public void sendReaction(String roomId, String reactionPayload) {
        // Dispara o payload direto via WebSocket para as sessões ativas da sala
        // sem persistência em banco para alta performance de animações de emojis
        webSocketHandler.broadcastReaction(roomId, reactionPayload);
    }
}
