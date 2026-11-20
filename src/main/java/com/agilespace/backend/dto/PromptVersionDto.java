package com.agilespace.backend.dto;

import java.time.Instant;
import java.util.List;

/** Versão de um item. Na lista só os campos de resumo vêm preenchidos; no detalhe, todos. */
public record PromptVersionDto(
        int versionNumber, String title, String changedBy, String changedByName, Instant changedAt, boolean current,
        String description, String content, String type, String visibility, String status, List<String> tags) {
}
