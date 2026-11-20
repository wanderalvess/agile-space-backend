package com.agilespace.backend.dto;

/** Resposta de POST /api/link-requests (201). */
public record LinkRequestCreatedDto(String id, String status) {
}
