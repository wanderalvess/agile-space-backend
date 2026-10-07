package com.agilespace.backend.repository;

import com.agilespace.backend.domain.WorkItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkItemRepository extends JpaRepository<WorkItem, String> {
    List<WorkItem> findBySquadId(String squadId);
    List<WorkItem> findBySquadIdAndSprintId(String squadId, String sprintId);
    List<WorkItem> findBySquadIdAndStatus(String squadId, String status);
    Optional<WorkItem> findBySquadIdAndJiraKey(String squadId, String jiraKey);
    List<WorkItem> findBySquadIdAndAssigneeId(String squadId, String assigneeId);

    @Query("SELECT w FROM WorkItem w WHERE w.squadId = :squadId AND (LOWER(w.assigneeId) = LOWER(:identifier) OR LOWER(w.assigneeName) = LOWER(:identifier) OR LOWER(w.devName) = LOWER(:identifier))")
    List<WorkItem> findBySquadIdAndUserIdentifier(@Param("squadId") String squadId, @Param("identifier") String identifier);
}
