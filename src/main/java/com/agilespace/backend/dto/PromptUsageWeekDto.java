package com.agilespace.backend.dto;

/** Uso de um item numa semana (segunda-feira UTC, YYYY-MM-DD). */
public record PromptUsageWeekDto(String weekStart, long uses, long clones) {
}
