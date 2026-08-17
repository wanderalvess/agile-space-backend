package com.agilespace.backend.repository;

import com.agilespace.backend.domain.DailyReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DailyReportRepository extends JpaRepository<DailyReport, String> {
    List<DailyReport> findByUserId(String userId);
}
