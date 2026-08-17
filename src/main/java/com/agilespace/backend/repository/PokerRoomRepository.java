package com.agilespace.backend.repository;

import com.agilespace.backend.domain.PokerRoom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PokerRoomRepository extends JpaRepository<PokerRoom, String> {
}
