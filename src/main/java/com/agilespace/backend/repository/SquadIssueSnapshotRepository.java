package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SquadIssueSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SquadIssueSnapshotRepository extends JpaRepository<SquadIssueSnapshot, String> {
    List<SquadIssueSnapshot> findBySquadId(String squadId);
    List<SquadIssueSnapshot> findBySquadIdAndSprintId(String squadId, String sprintId);
    List<SquadIssueSnapshot> findBySquadIdAndAssigneeId(String squadId, String assigneeId);
    List<SquadIssueSnapshot> findBySquadIdAndCeremonyStatus(String squadId, String ceremonyStatus);
    Optional<SquadIssueSnapshot> findBySquadIdAndJiraKey(String squadId, String jiraKey);
    void deleteBySquadIdAndJiraKeyIn(String squadId, List<String> keys);

    @Query("SELECT s FROM SquadIssueSnapshot s WHERE s.squadId = :squadId AND (LOWER(s.assigneeId) = LOWER(:identifier) OR LOWER(s.assigneeName) = LOWER(:identifier))")
    List<SquadIssueSnapshot> findBySquadIdAndAssigneeIdentifier(@Param("squadId") String squadId, @Param("identifier") String identifier);
}
