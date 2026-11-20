package com.agilespace.backend.dto;

/**
 * Corpo de POST /api/link-requests. {@code memberId}, {@code message}, {@code kind} e {@code requestedRoleName} são
 * opcionais (contrato: docs/link-requests-squad-api.md). {@code requestedRoleName} só vale sem {@code memberId}.
 */
public record LinkRequestCreateRequest(String projectKey, String memberId, String emailInJira, String message,
                                       String kind, String requestedRoleName) {

    /** Forma anterior à V76 (sem tipo nem papel pedido). */
    public LinkRequestCreateRequest(String projectKey, String memberId, String emailInJira, String message) {
        this(projectKey, memberId, emailInJira, message, null, null);
    }
}
