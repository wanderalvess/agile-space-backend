package com.agilespace.backend.repository;

import com.agilespace.backend.domain.PokerChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PokerChatMessageRepository extends JpaRepository<PokerChatMessage, String> {
    List<PokerChatMessage> findByRoomIdAndChannelIdOrderByTsAsc(String roomId, String channelId);
    List<PokerChatMessage> findByRoomIdAndChannelIdOrderByTsDesc(String roomId, String channelId, Pageable pageable);
    void deleteByRoomId(String roomId);
}
