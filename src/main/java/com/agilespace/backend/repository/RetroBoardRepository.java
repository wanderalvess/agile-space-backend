package com.agilespace.backend.repository;

import com.agilespace.backend.domain.RetroBoard;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RetroBoardRepository extends JpaRepository<RetroBoard, String> {
    List<RetroBoard> findBySprintIdOrderByCreatedAtDesc(String sprintId);
    List<RetroBoard> findByTeamIgnoreCaseOrderByCreatedAtDesc(String team);
    List<RetroBoard> findBySquadIdIgnoreCaseOrderByCreatedAtDesc(String squadId);

    /** Lock pessimista na linha do board: serializa votos concorrentes (limite por painel). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from RetroBoard b where b.id = :id")
    Optional<RetroBoard> findByIdForUpdate(@Param("id") String id);

    List<RetroBoard> findBySquadIdIgnoreCaseOrderByCreatedAtDesc(String squadId, Pageable pageable);

    /** Home: quadros das squads (ids em MAIÚSCULAS) com escrita desde {@code since}. */
    @Query("SELECT b FROM RetroBoard b WHERE UPPER(b.squadId) IN :squadIds AND b.updatedAt >= :since ORDER BY b.updatedAt DESC")
    List<RetroBoard> findActiveBySquads(@Param("squadIds") Collection<String> upperSquadIds, @Param("since") LocalDateTime since, Pageable pageable);
}
