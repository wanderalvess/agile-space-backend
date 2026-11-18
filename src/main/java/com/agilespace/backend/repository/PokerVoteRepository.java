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

    /** Voto mais recente da sala (ISO em texto, então o maior texto é o mais novo); null sem votos. */
    @org.springframework.data.jpa.repository.Query("SELECT MAX(v.timestamp) FROM PokerVote v WHERE v.roomId = :roomId")
    String latestTimestamp(@org.springframework.data.repository.query.Param("roomId") String roomId);
}
