package com.agilespace.backend.repository;

import com.agilespace.backend.domain.RetroParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RetroParticipantRepository extends JpaRepository<RetroParticipant, String> {
    List<RetroParticipant> findByBoardId(String boardId);
    Optional<RetroParticipant> findByBoardIdAndId(String boardId, String id);
    void deleteByBoardIdAndId(String boardId, String id);
    void deleteByBoardId(String boardId);

    @Query("SELECT p.boardId, COUNT(p) FROM RetroParticipant p WHERE p.boardId IN :boardIds GROUP BY p.boardId")
    List<Object[]> countByBoard(@Param("boardIds") Collection<String> boardIds);
}
