package com.agilespace.backend.repository;

import com.agilespace.backend.domain.PokerRound;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PokerRoundRepository extends JpaRepository<PokerRound, String> {
    List<PokerRound> findByRoomId(String roomId);
    
    @Query("SELECT r FROM PokerRound r WHERE r.roomId = :roomId ORDER BY r.timestamp DESC")
    List<PokerRound> findRecentRounds(@Param("roomId") String roomId, Pageable pageable);
    
    void deleteByRoomId(String roomId);
}
