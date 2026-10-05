package com.agilespace.backend.repository;

import com.agilespace.backend.domain.RetroChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RetroChatMessageRepository extends JpaRepository<RetroChatMessage, String> {
    List<RetroChatMessage> findByBoardIdAndChannelIdOrderByTsAsc(String boardId, String channelId);
    List<RetroChatMessage> findByBoardIdAndChannelIdOrderByTsDesc(String boardId, String channelId, Pageable pageable);
    void deleteByBoardId(String boardId);
}
