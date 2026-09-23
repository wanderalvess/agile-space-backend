package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SprintPlanningMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SprintPlanningMemberRepository extends JpaRepository<SprintPlanningMember, String> {
    List<SprintPlanningMember> findByPlanningIdOrderByOrderAsc(String planningId);
    void deleteByPlanningId(String planningId);
}
