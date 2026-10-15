package com.agilespace.backend.dto;

import java.time.Instant;
import java.util.List;

/** Contrato: docs/home-api.md §1. {@code dismissed} só tem sentido nas rotas do usuário (false nas de admin). */
public record AnnouncementDto(
        String id,
        String title,
        String content,
        String type,
        String audienceType,
        List<String> audienceSquadIds,
        List<String> audienceRoles,
        Instant validFrom,
        Instant validUntil,
        String link,
        String createdBy,
        String createdByName,
        Instant createdAt,
        boolean dismissed) {
}
