package com.agilespace.backend.repository;

import com.agilespace.backend.domain.RetroBoard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RetroBoardRepository extends JpaRepository<RetroBoard, String> {
    List<RetroBoard> findBySprintIdOrderByCreatedAtDesc(String sprintId);
    List<RetroBoard> findByTeamOrderByCreatedAtDesc(String team);
    List<RetroBoard> findBySquadIdOrderByCreatedAtDesc(String squadId);
}
