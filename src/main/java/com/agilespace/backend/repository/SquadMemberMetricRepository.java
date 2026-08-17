package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SquadMemberMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SquadMemberMetricRepository extends JpaRepository<SquadMemberMetric, String> {
    List<SquadMemberMetric> findBySquadId(String squadId);
    void deleteBySquadId(String squadId);
}
