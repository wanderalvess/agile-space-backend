package com.agilespace.backend.dto;

import java.util.List;

/** Contrato: docs/home-api.md §7. */
public record RitualFactsDto(String tool, List<String> facts, int openCount, RitualSessionDto last) {
}
