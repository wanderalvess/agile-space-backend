package com.agilespace.backend.repository;

import com.agilespace.backend.domain.ActionPlanTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ActionPlanTaskRepository extends JpaRepository<ActionPlanTask, UUID> {
    List<ActionPlanTask> findByBoardIdOrderByOrderAsc(UUID boardId);

    void deleteByBoardId(UUID boardId);

    /** Total e concluídas por plano, numa consulta só (listagens; sem N+1). Plano sem ação não aparece. */
    @Query("SELECT t.boardId AS boardId, COUNT(t) AS total, "
            + "SUM(CASE WHEN LOWER(t.status) = 'done' THEN 1 ELSE 0 END) AS done "
            + "FROM ActionPlanTask t WHERE t.boardId IN :boardIds GROUP BY t.boardId")
    List<BoardTaskCount> countByBoards(@Param("boardIds") Collection<UUID> boardIds);

    interface BoardTaskCount {
        UUID getBoardId();

        Long getTotal();

        Long getDone();
    }
}
