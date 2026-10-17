package com.agilespace.backend.dto;

import java.util.List;

/** Resposta de {@code POST /api/admin/membership/lead-restore} (Etapa 4; contrato em docs/membership-api.md). */
public record LeadRestoreDto(int restored, int skipped, List<Item> users) {

    public static final String RESTORED = "RESTORED";
    public static final String SKIPPED = "SKIPPED";

    public static final String ALREADY_LEAD = "ALREADY_LEAD";
    public static final String CURRENT_ROLE_NOT_MEMBER = "CURRENT_ROLE_NOT_MEMBER";
    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";
    public static final String NOT_IN_BACKUP = "NOT_IN_BACKUP";

    /** @param reason null quando RESTORED */
    public record Item(String userId, String email, String result, String reason) { }
}
