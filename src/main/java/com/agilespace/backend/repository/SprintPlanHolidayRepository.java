package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SprintPlanHoliday;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SprintPlanHolidayRepository extends JpaRepository<SprintPlanHoliday, SprintPlanHoliday.Key> {
    List<SprintPlanHoliday> findByPlanIdOrderByDayAsc(String planId);
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from SprintPlanHoliday e where e.planId = :planId")
    int deleteAllForPlan(@org.springframework.data.repository.query.Param("planId") String planId);
}
