package com.agilespace.backend.repository;

import com.agilespace.backend.domain.RetroParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RetroParticipantRepository extends JpaRepository<RetroParticipant, String> {
    List<RetroParticipant> findByBoardId(String boardId);
    Optional<RetroParticipant> findByBoardIdAndId(String boardId, String id);
    void deleteByBoardIdAndId(String boardId, String id);
}
