package com.agilespace.backend.dto;

import java.util.List;
import java.util.Map;

public record PromptFacetsDto(long total, Map<String, Long> typeCounts, List<TagCount> topTags, Totals totals) {

    public record TagCount(String tag, long count) {}

    /** KPIs gerais do que o usuário enxerga (não dependem dos filtros): itens, usos/cópias e coleções. */
    public record Totals(long items, long copies, long collections) {}
}
