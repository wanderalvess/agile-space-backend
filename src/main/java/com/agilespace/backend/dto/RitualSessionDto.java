package com.agilespace.backend.dto;

import java.time.Instant;

/** Contrato: docs/home-api.md §6. */
public record RitualSessionDto(
        String tool,
        String id,
        String title,
        String squadId,
        Instant date,
        int participantsCount,
        String status,
        String summary,
        Long durationMinutes,
        String link) {
}
