package com.agilespace.backend.dto;

import java.time.Instant;

/** Pedido de vínculo visto pelo admin (GET /api/admin/link-requests). Contrato: docs/link-requests-api.md e docs/link-requests-squad-api.md. */
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
        String decisionNote,
        String kind,
        String requestedRoleName,
        Boolean canDecide) {

    /** Forma anterior à V76 (sem tipo, papel pedido nem canDecide). */
    public AdminLinkRequestDto(String id, Requester requester, String projectKey, String memberId, String memberName,
                               String emailInJira, String message, String status, Instant createdAt, Instant decidedAt,
                               String decidedBy, String decisionNote) {
        this(id, requester, projectKey, memberId, memberName, emailInJira, message, status, createdAt, decidedAt,
                decidedBy, decisionNote, "CONTRIBUTOR", null, null);
    }

    /** Quem pediu (conta do portal). */
    public record Requester(String id, String name, String email) { }
}
