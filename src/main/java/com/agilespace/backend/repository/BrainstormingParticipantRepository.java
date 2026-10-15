package com.agilespace.backend.repository;

import com.agilespace.backend.domain.BrainstormingParticipant;
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
public interface BrainstormingParticipantRepository extends JpaRepository<BrainstormingParticipant, String> {
    List<BrainstormingParticipant> findByBoardIdOrderByNicknameAsc(String boardId);
    Optional<BrainstormingParticipant> findByBoardIdAndId(String boardId, String id);
    void deleteByBoardIdAndId(String boardId, String id);
    void deleteByBoardId(String boardId);

    /** [boardId, participantes, última atividade (ISO)]. */
    @Query("SELECT p.boardId, COUNT(p), MAX(p.lastActive) FROM BrainstormingParticipant p WHERE p.boardId IN :boardIds GROUP BY p.boardId")
    List<Object[]> summarizeByBoard(@Param("boardIds") Collection<String> boardIds);
}
