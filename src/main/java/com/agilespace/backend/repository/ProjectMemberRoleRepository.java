package com.agilespace.backend.repository;

import com.agilespace.backend.domain.ProjectMemberRole;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectMemberRoleRepository extends JpaRepository<ProjectMemberRole, String> {

    List<ProjectMemberRole> findByProjectId(String projectId);

    List<ProjectMemberRole> findByEmailIgnoreCase(String email);

    List<ProjectMemberRole> findByJiraAccountId(String jiraAccountId);

    List<ProjectMemberRole> findByUserId(String userId);

    @Query("SELECT pmr FROM ProjectMemberRole pmr WHERE LOWER(pmr.email) = LOWER(:identifier) OR pmr.jiraAccountId = :identifier")
    List<ProjectMemberRole> findByEmailOrJiraAccountId(@Param("identifier") String identifier);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ProjectMemberRole pmr WHERE pmr.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") String projectId);

    @Query("SELECT pmr FROM ProjectMemberRole pmr WHERE UPPER(pmr.projectId) IN :ids")
    List<ProjectMemberRole> findByProjectIdsUpper(@Param("ids") Collection<String> upperProjectIds);

    @Query("SELECT pmr FROM ProjectMemberRole pmr WHERE UPPER(pmr.roleKey) IN :keys")
    List<ProjectMemberRole> findByRoleKeysUpper(@Param("keys") Collection<String> upperRoleKeys);
}
