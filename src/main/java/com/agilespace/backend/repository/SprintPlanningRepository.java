package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SprintPlanning;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SprintPlanningRepository extends JpaRepository<SprintPlanning, String> {

    // isReadyForPoker vive dentro do JSONB "settings" (nunca foi coluna própria); comparação textual pois o valor é serializado como "true"/"false".
    @Query(value = "SELECT * FROM sprint_plannings WHERE settings->>'isReadyForPoker' = 'true' ORDER BY created_at DESC LIMIT :limit", nativeQuery = true)
    List<SprintPlanning> findReadyForPoker(@Param("limit") int limit);
}
