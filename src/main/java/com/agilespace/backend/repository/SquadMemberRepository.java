package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SquadMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SquadMemberRepository extends JpaRepository<SquadMember, String> {
    List<SquadMember> findBySquadIdOrderByDisplayNameAsc(String squadId);
    Optional<SquadMember> findBySquadIdAndJiraAccountId(String squadId, String jiraAccountId);
}
