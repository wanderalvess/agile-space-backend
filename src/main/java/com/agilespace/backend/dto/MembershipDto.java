package com.agilespace.backend.dto;

import java.util.List;

/**
 * Um vínculo da pessoa com UMA squad (contrato de GET /api/me/memberships, docs/membership-api.md).
 *
 * <p>Os campos de papel no topo ({@code roleName}, {@code roleKey}, {@code leadership}, {@code source}) são os do papel
 * <b>primário</b> da pessoa na squad; {@code roles} traz todos os papéis (inclusive o primário, marcado
 * {@code primary=true}). {@code scope}: SQUAD (linha própria na squad), TRIBE ou SEGMENT (alcançada por liderança
 * transversal; {@code direct=false}).
 */
public record MembershipDto(
        String projectId,
        String projectName,
        String segmentName,
        String tribeName,
        String roleName,
        String roleKey,
        boolean leadership,
        boolean direct,
        String scope,
        String source,
        List<RoleDto> roles) {

    public static final String SCOPE_SQUAD = "SQUAD";
    public static final String SCOPE_TRIBE = "TRIBE";
    public static final String SCOPE_SEGMENT = "SEGMENT";

    /** Papel da pessoa numa squad. {@code source} nulo = linha anterior à V75 (origem desconhecida). */
    public record RoleDto(String roleName, String roleKey, boolean leadership, String source, boolean primary) { }
}
