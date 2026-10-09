package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SquadMemberExclusion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SquadMemberExclusionRepository extends JpaRepository<SquadMemberExclusion, String> {
    List<SquadMemberExclusion> findBySquadId(String squadId);
}
