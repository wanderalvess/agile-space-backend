package com.agilespace.backend.repository;

import com.agilespace.backend.domain.RetroCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RetroCardRepository extends JpaRepository<RetroCard, String> {
    List<RetroCard> findByBoardId(String boardId);
    void deleteByBoardId(String boardId);
}
