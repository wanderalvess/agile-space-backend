package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SquadIssueWorklogCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SquadIssueWorklogCacheRepository extends JpaRepository<SquadIssueWorklogCache, String> {
    List<SquadIssueWorklogCache> findBySquadIdAndSprintId(String squadId, String sprintId);
    List<SquadIssueWorklogCache> findBySquadId(String squadId);
}
