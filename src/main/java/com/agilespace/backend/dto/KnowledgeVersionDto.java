package com.agilespace.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Versão de um documento. Na listagem {@code content} e {@code tags} vêm nulos (só metadados). */
public record KnowledgeVersionDto(
        int versionNumber,
        String title,
        String changedBy,
        String changedByName,
        LocalDateTime changedAt,
        boolean current,
        String content,
        List<String> tags,
        String category,
        String fullPath) {
}
