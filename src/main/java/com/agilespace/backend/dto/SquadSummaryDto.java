package com.agilespace.backend.dto;

import java.time.Instant;

/** Contrato: docs/home-api.md §7. */
public record SquadSummaryDto(
        String squadId,
        String name,
        String sprintName,
        Integer sprintDaysLeft,
        Integer sprintProgressPct,
        int liveCount,
        NextCeremony nextCeremony) {

    public record NextCeremony(String name, Instant startsAt) {
    }
}
