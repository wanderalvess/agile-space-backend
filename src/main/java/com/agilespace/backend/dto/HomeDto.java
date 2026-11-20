package com.agilespace.backend.dto;

import java.util.List;

/** Contrato: docs/home-api.md §7 (GET /api/home). */
public record HomeDto(
        List<AnnouncementDto> announcements,
        UnreadCountDto unread,
        boolean multiSquad,
        List<SquadSummaryDto> squads,
        List<LiveSessionDto> liveSessions,
        List<RitualFactsDto> rituals) {
}
