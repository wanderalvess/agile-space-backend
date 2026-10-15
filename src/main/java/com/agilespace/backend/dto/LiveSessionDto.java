package com.agilespace.backend.dto;

import java.time.Instant;

/** Contrato: docs/home-api.md §5. tool/status são os nomes dos enums de SessionStatusResolver. */
public record LiveSessionDto(
        String tool,
        String id,
        String title,
        String squadId,
        String squadName,
        int participantsCount,
        String status,
        String link,
        Instant startedAt,
        Instant updatedAt) {
}
