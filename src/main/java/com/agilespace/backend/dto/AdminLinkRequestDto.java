package com.agilespace.backend.dto;

import java.time.Instant;

/** Pedido de vínculo visto pelo admin (GET /api/admin/link-requests). Contrato: docs/link-requests-api.md. */
public record AdminLinkRequestDto(
        String id,
        Requester requester,
        String projectKey,
        String memberId,
        String memberName,
        String emailInJira,
        String message,
        String status,
        Instant createdAt,
        Instant decidedAt,
        String decidedBy,
        String decisionNote) {

    /** Quem pediu (conta do portal). */
    public record Requester(String id, String name, String email) { }
}
