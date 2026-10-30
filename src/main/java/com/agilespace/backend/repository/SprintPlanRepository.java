package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SprintPlan;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SprintPlanRepository extends JpaRepository<SprintPlan, String> {
    List<SprintPlan> findBySquadIdOrderByStartDateDesc(String squadId);
    boolean existsBySquadIdAndJiraSprintId(String squadId, String jiraSprintId);
    Optional<SprintPlan> findFirstBySquadIdAndStatusInOrderByStartDateDesc(String squadId, Collection<String> statuses);

    /** Trava a linha do plano até o fim da transação: claim/liberar/datas da distribuição se serializam por plano. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM SprintPlan p WHERE p.id = :id")
    Optional<SprintPlan> findByIdForUpdate(@Param("id") String id);
}
