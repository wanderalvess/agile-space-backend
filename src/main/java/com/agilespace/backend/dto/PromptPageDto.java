package com.agilespace.backend.dto;

import java.util.List;

/** Envelope paginado do contrato novo da Biblioteca de IA. */
public record PromptPageDto<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
}
