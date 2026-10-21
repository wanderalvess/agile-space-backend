package com.agilespace.backend.repository;

/**
 * Filtros da listagem/facets. Todos opcionais. visibility: all (padrão) | public | private;
 * status: omitido = sem arquivados ("arquivado" ou "archived"), "all" = todos.
 */
public record PromptFilter(String query, String type, String visibility, String status, String impact,
                           String tag, boolean favorites, String authorId) {

    public PromptFilter withoutType() {
        return new PromptFilter(query, null, visibility, status, impact, tag, favorites, authorId);
    }

    public PromptFilter withoutTag() {
        return new PromptFilter(query, type, visibility, status, impact, null, favorites, authorId);
    }
}
