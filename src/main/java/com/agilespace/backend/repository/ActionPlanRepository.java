package com.agilespace.backend.repository;

import com.agilespace.backend.domain.ActionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ActionPlanRepository extends JpaRepository<ActionPlan, UUID> {
    List<ActionPlan> findBySprintIdOrderByCreatedAtDesc(String sprintId);

    @Query("SELECT p FROM ActionPlan p WHERE UPPER(p.squadId) = UPPER(:squadId) ORDER BY p.createdAt DESC")
    List<ActionPlan> findBySquad(@Param("squadId") String squadId, Pageable pageable);

    /** Planos da squad de um tipo de origem; plano antigo (tipo nulo) conta como MANUAL. */
    @Query("SELECT p FROM ActionPlan p WHERE UPPER(p.squadId) = UPPER(:squadId) AND COALESCE(p.sourceType, 'MANUAL') = :sourceType ORDER BY p.createdAt DESC")
    List<ActionPlan> findBySquadAndSourceType(@Param("squadId") String squadId, @Param("sourceType") String sourceType, Pageable pageable);

    @Query("SELECT p FROM ActionPlan p WHERE UPPER(p.squadId) = UPPER(:squadId) AND p.sourceType = :sourceType AND p.sourceId = :sourceId ORDER BY p.createdAt DESC")
    List<ActionPlan> findBySquadAndSource(@Param("squadId") String squadId, @Param("sourceType") String sourceType,
                                          @Param("sourceId") String sourceId, Pageable pageable);

    /** Busca por origem sem informar a squad (o chamador filtra visibilidade e acesso à squad). */
    @Query("SELECT p FROM ActionPlan p WHERE p.sourceType = :sourceType AND p.sourceId = :sourceId ORDER BY p.createdAt DESC")
    List<ActionPlan> findBySource(@Param("sourceType") String sourceType, @Param("sourceId") String sourceId, Pageable pageable);

    /**
     * Plano da mesma origem no mesmo escopo; {@code scope} é a squad em maiúsculas ou '' para plano sem squad — a mesma
     * expressão do índice único parcial uq_action_plans_source (V78).
     */
    @Query("SELECT p FROM ActionPlan p WHERE COALESCE(UPPER(p.squadId), '') = :scope AND p.sourceType = :sourceType AND p.sourceId = :sourceId")
    List<ActionPlan> findBySourceInScope(@Param("scope") String scope, @Param("sourceType") String sourceType,
                                         @Param("sourceId") String sourceId);
}
