package com.agilespace.backend.dto;

import java.time.Instant;

/** Pedido de vínculo visto por quem pediu (GET /api/link-requests/mine). Contrato: docs/link-requests-api.md. */
public record LinkRequestDto(
        String id,
        String projectKey,
        String memberId,
        String memberName,
        String emailInJira,
        String message,
        String status,
        Instant createdAt,
        Instant decidedAt,
        String decisionNote,
        String kind,
        String requestedRoleName) {

    /** Forma anterior à V76. */
    public LinkRequestDto(String id, String projectKey, String memberId, String memberName, String emailInJira,
                          String message, String status, Instant createdAt, Instant decidedAt, String decisionNote) {
        this(id, projectKey, memberId, memberName, emailInJira, message, status, createdAt, decidedAt, decisionNote,
                "CONTRIBUTOR", null);
    }
}
