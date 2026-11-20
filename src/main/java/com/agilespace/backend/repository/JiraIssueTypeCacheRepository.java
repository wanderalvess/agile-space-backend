package com.agilespace.backend.repository;

import com.agilespace.backend.domain.JiraIssueTypeCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface JiraIssueTypeCacheRepository extends JpaRepository<JiraIssueTypeCache, JiraIssueTypeCache.Key> {
    List<JiraIssueTypeCache> findBySquadIdOrderByNameAsc(String squadId);

    @Modifying
    @Transactional
    void deleteBySquadId(String squadId);
}
