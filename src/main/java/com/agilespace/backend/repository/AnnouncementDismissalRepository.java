package com.agilespace.backend.repository;

import com.agilespace.backend.domain.AnnouncementDismissal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Set;

@Repository
public interface AnnouncementDismissalRepository extends JpaRepository<AnnouncementDismissal, AnnouncementDismissal.Key> {

    @Query("SELECT d.announcementId FROM AnnouncementDismissal d WHERE d.userId = :userId")
    Set<String> findDismissedIds(@Param("userId") String userId);
}
