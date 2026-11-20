package com.agilespace.backend.repository;

import com.agilespace.backend.domain.RetroCard;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RetroCardRepository extends JpaRepository<RetroCard, String> {
    List<RetroCard> findByBoardId(String boardId);
    List<RetroCard> findByBoardIdAndParentId(String boardId, String parentId);
    void deleteByBoardId(String boardId);

    /** [boardId, columnKey, isDone, quantidade] para o resumo do histórico. */
    @Query("SELECT c.boardId, c.columnKey, c.isDone, COUNT(c) FROM RetroCard c WHERE c.boardId IN :boardIds GROUP BY c.boardId, c.columnKey, c.isDone")
    List<Object[]> countByBoardColumnAndDone(@Param("boardIds") Collection<String> boardIds);
}
