package com.agilespace.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Resposta de GET /api/me/memberships. {@code contractVersion} sobe quando um campo é removido ou muda de sentido
 * (acrescentar campo não muda a versão).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MembershipsResponseDto(
        String contractVersion,
        String activeProjectId,
        boolean legacyOnly,
        boolean transversalLeader,
        List<MembershipDto> memberships) {

    public static final String CONTRACT_VERSION = "1";
    public static final String CONTRACT_HEADER = "X-Membership-Contract";
}
