package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SquadMember;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SquadMemberRepository extends JpaRepository<SquadMember, String> {
    List<SquadMember> findBySquadIdOrderByDisplayNameAsc(String squadId);
    Optional<SquadMember> findBySquadIdAndJiraAccountId(String squadId, String jiraAccountId);
    List<SquadMember> findByClaimedByUid(String uid);

    @Query("SELECT sm FROM SquadMember sm WHERE LOWER(sm.email) = LOWER(:identifier) OR LOWER(sm.jiraAccountId) = LOWER(:identifier) OR LOWER(sm.displayName) = LOWER(:identifier)")
    List<SquadMember> findByUserIdentifier(@Param("identifier") String identifier);

    @Query("SELECT sm FROM SquadMember sm WHERE UPPER(sm.squadId) IN :ids")
    List<SquadMember> findBySquadIdsUpper(@Param("ids") Collection<String> upperSquadIds);
}
