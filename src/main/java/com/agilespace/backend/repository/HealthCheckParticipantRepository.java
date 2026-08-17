package com.agilespace.backend.repository;

import com.agilespace.backend.domain.HealthCheckParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HealthCheckParticipantRepository extends JpaRepository<HealthCheckParticipant, String> {
    List<HealthCheckParticipant> findByBoardIdOrderByNicknameAsc(String boardId);
    Optional<HealthCheckParticipant> findByBoardIdAndId(String boardId, String id);
    void deleteByBoardIdAndId(String boardId, String id);
    void deleteByBoardId(String boardId);
}
