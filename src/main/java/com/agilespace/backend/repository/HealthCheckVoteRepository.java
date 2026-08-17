package com.agilespace.backend.repository;

import com.agilespace.backend.domain.HealthCheckVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HealthCheckVoteRepository extends JpaRepository<HealthCheckVote, String> {
    List<HealthCheckVote> findByBoardId(String boardId);
    List<HealthCheckVote> findByBoardIdAndParticipantId(String boardId, String participantId);
    void deleteByBoardId(String boardId);
}
