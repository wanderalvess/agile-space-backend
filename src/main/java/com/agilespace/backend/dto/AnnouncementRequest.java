package com.agilespace.backend.dto;

import java.time.Instant;
import java.util.List;

/** Corpo de POST/PUT /api/admin/announcements (docs/home-api.md §1). */
public record AnnouncementRequest(
        String title,
        String content,
        String type,
        String audienceType,
        List<String> audienceSquadIds,
        List<String> audienceRoles,
        Instant validFrom,
        Instant validUntil,
        String link) {
}
