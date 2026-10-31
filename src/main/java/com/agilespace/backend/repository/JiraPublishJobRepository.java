package com.agilespace.backend.repository;

import com.agilespace.backend.domain.JiraPublishJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JiraPublishJobRepository extends JpaRepository<JiraPublishJob, String> {
    List<JiraPublishJob> findByRoomIdOrderByCreatedAtDesc(String roomId);
    List<JiraPublishJob> findByRoomIdAndIssueIdOrderByCreatedAtDesc(String roomId, String issueId);
    /** Fila da squad inteira (todas as salas), mais recentes primeiro; o serviço limita pelo Pageable. */
    List<JiraPublishJob> findBySquadIdIgnoreCaseOrderByCreatedAtDesc(String squadId, org.springframework.data.domain.Pageable pageable);
}
