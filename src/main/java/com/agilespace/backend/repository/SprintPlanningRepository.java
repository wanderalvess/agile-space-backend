package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SprintPlanning;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SprintPlanningRepository extends JpaRepository<SprintPlanning, String> {

    // settings.isReadyForPoker é coluna própria (ready_for_poker) desde a migração de JSONB
    // pra colunas/tabelas relacionais — antes vivia em settings->>'isReadyForPoker' via query nativa.
    @Query("SELECT s FROM SprintPlanning s WHERE s.settings.isReadyForPoker = true ORDER BY s.createdAt DESC")
    List<SprintPlanning> findReadyForPoker(Pageable pageable);
}
