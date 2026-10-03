package com.agilespace.backend.repository;

import com.agilespace.backend.domain.PokerRoom;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PokerRoomRepository extends JpaRepository<PokerRoom, String> {
    Page<PokerRoom> findByTeamIgnoreCaseOrderByCreatedAtDesc(String team, Pageable pageable);
}
