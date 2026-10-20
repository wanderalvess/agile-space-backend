package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SquadSubtaskTypeMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SquadSubtaskTypeMappingRepository extends JpaRepository<SquadSubtaskTypeMapping, String> {
    List<SquadSubtaskTypeMapping> findBySquadIdAndSprintKey(String squadId, String sprintKey);

    /** Todas as linhas da squad, a mais recente primeiro (para achar a sprint anterior a herdar). */
    List<SquadSubtaskTypeMapping> findBySquadIdOrderByUpdatedAtDesc(String squadId);
}
