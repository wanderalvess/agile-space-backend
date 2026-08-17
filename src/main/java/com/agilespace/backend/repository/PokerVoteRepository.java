package com.agilespace.backend.repository;

import com.agilespace.backend.domain.PokerVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PokerVoteRepository extends JpaRepository<PokerVote, String> {
    List<PokerVote> findByRoomId(String roomId);
    Optional<PokerVote> findByRoomIdAndParticipantId(String roomId, String participantId);
    void deleteByRoomIdAndParticipantId(String roomId, String participantId);
    void deleteByRoomId(String roomId);
}
