package com.agilespace.backend.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Resposta de GET /api/admin/membership/shadow-diff: legado x novo para todos os usuários ativos (docs/membership-api.md). */
public record MembershipShadowDiffDto(
        Instant generatedAt,
        String mode,
        int sampleLimit,
        int usersChecked,
        int usersWithDivergence,
        int usersFailed,
        Map<String, Integer> totals,
        List<Sample> sample,
        boolean truncated) {

    /** Amostra categórica: só ids e rótulos, sem e-mail nem nome. */
    public record Sample(String userId, String squad, String type, String detail) { }
}
