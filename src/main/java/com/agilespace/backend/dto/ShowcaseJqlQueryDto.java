package com.agilespace.backend.dto;

import java.time.LocalDateTime;

/** Consulta JQL salva da Review, como a tela a vê (docs/ceremony-review-api.md). */
public record ShowcaseJqlQueryDto(String id, String squadId, String name, String jql, String visibility,
                                  String createdBy, String createdByName, LocalDateTime createdAt,
                                  boolean mine, boolean canDelete) {
}
