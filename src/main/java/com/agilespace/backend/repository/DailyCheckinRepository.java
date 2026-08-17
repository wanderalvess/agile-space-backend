package com.agilespace.backend.repository;

import com.agilespace.backend.domain.DailyCheckin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DailyCheckinRepository extends JpaRepository<DailyCheckin, String> {
    List<DailyCheckin> findBySquadIdAndDate(String squadId, String date);
    List<DailyCheckin> findBySquadIdAndDateBetween(String squadId, String startDate, String endDate);
}
