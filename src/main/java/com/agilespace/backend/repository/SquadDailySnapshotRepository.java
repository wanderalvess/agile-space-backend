package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SquadDailySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SquadDailySnapshotRepository extends JpaRepository<SquadDailySnapshot, String> {
    List<SquadDailySnapshot> findBySquadIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(String squadId, String since);
}
