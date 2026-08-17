package com.agilespace.backend.repository;

import com.agilespace.backend.domain.PokerParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PokerParticipantRepository extends JpaRepository<PokerParticipant, String> {
    List<PokerParticipant> findByRoomIdOrderByNicknameAsc(String roomId);
    Optional<PokerParticipant> findByRoomIdAndId(String roomId, String id);
    void deleteByRoomIdAndId(String roomId, String id);
    void deleteByRoomId(String roomId);
}
