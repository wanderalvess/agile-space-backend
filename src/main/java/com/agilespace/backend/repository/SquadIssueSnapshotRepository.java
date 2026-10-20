package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SquadIssueSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * As listagens "da squad" (sem sprint) deixam de fora as linhas que só existem pelo backlog do Sprint Planner
 * (sprint_id = 'PLANNER_BACKLOG'): Quadro, Cronograma, itens por pessoa e o sync da sprint continuam vendo o mesmo
 * conjunto de antes da V89.
 */
@Repository
public interface SquadIssueSnapshotRepository extends JpaRepository<SquadIssueSnapshot, String> {
    @Query("SELECT s FROM SquadIssueSnapshot s WHERE s.squadId = :squadId AND (s.sprintId IS NULL OR s.sprintId <> 'PLANNER_BACKLOG')")
    List<SquadIssueSnapshot> findBySquadId(@Param("squadId") String squadId);

    List<SquadIssueSnapshot> findBySquadIdAndSprintId(String squadId, String sprintId);

    @Query("SELECT s FROM SquadIssueSnapshot s WHERE s.squadId = :squadId AND s.assigneeId = :assigneeId AND (s.sprintId IS NULL OR s.sprintId <> 'PLANNER_BACKLOG')")
    List<SquadIssueSnapshot> findBySquadIdAndAssigneeId(@Param("squadId") String squadId, @Param("assigneeId") String assigneeId);

    @Query("SELECT s FROM SquadIssueSnapshot s WHERE s.squadId = :squadId AND s.ceremonyStatus = :ceremonyStatus AND (s.sprintId IS NULL OR s.sprintId <> 'PLANNER_BACKLOG')")
    List<SquadIssueSnapshot> findBySquadIdAndCeremonyStatus(@Param("squadId") String squadId, @Param("ceremonyStatus") String ceremonyStatus);

    Optional<SquadIssueSnapshot> findBySquadIdAndJiraKey(String squadId, String jiraKey);
    @org.springframework.transaction.annotation.Transactional
    void deleteBySquadIdAndJiraKeyIn(String squadId, List<String> keys);

    @Query("SELECT s FROM SquadIssueSnapshot s WHERE s.squadId = :squadId AND (LOWER(s.assigneeId) = LOWER(:identifier) OR LOWER(s.assigneeName) = LOWER(:identifier)) AND (s.sprintId IS NULL OR s.sprintId <> 'PLANNER_BACKLOG')")
    List<SquadIssueSnapshot> findBySquadIdAndAssigneeIdentifier(@Param("squadId") String squadId, @Param("identifier") String identifier);

    // --- Sprint Planner ---
    List<SquadIssueSnapshot> findBySquadIdAndInPlannerBacklogTrue(String squadId);

    List<SquadIssueSnapshot> findBySquadIdAndJiraKeyIn(String squadId, Collection<String> keys);
}
