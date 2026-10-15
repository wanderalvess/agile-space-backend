package com.agilespace.backend.dto;

import java.time.Instant;

/** Contrato: docs/home-api.md §2. */
public record NotificationDto(
        String id,
        String kind,
        String title,
        String text,
        String link,
        String actorId,
        String actorName,
        String actorAvatarUrl,
        String squadId,
        Instant createdAt,
        Instant readAt) {
}
