package com.agilespace.backend.repository;

import com.agilespace.backend.domain.GlobalAnnouncement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface GlobalAnnouncementRepository extends JpaRepository<GlobalAnnouncement, String> {
    List<GlobalAnnouncement> findByOrderByCreatedAtDesc();

    /** Já começaram a valer (validFrom ≤ agora), vigentes ou expirados; o público é filtrado no serviço. */
    @Query("SELECT a FROM GlobalAnnouncement a WHERE a.validFrom <= :now ORDER BY a.createdAt DESC")
    List<GlobalAnnouncement> findStarted(@Param("now") LocalDateTime now);

    /** Vigentes agora: validFrom ≤ agora < validUntil (validUntil null = sem fim). */
    @Query("SELECT a FROM GlobalAnnouncement a WHERE a.validFrom <= :now AND (a.validUntil IS NULL OR a.validUntil > :now) "
            + "ORDER BY a.createdAt DESC")
    List<GlobalAnnouncement> findActive(@Param("now") LocalDateTime now);
}
