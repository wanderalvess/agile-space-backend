package com.agilespace.backend.repository;

import com.agilespace.backend.domain.UserAvailability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserAvailabilityRepository extends JpaRepository<UserAvailability, String> {
    List<UserAvailability> findBySquadIdAndDate(String squadId, String date);
    List<UserAvailability> findBySquadIdAndDateBetween(String squadId, String startDate, String endDate);
}
