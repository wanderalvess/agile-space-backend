package com.agilespace.backend.dto;

import java.time.LocalDateTime;

/**
 * Origem do texto de um campo do card. O cliente manda {@code field}, {@code sourceKind}, {@code sourceLabel} e
 * {@code originalText}; {@code importedBy} e {@code importedAt} são do servidor.
 */
public record ShowcaseFieldOriginDto(String field, String sourceKind, String sourceLabel, String originalText,
                                     String importedBy, LocalDateTime importedAt) {
}
