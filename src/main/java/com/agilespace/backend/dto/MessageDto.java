package com.agilespace.backend.dto;

import java.time.Instant;

/** Contrato: docs/home-api.md §3. */
public record MessageDto(String id, String conversationId, String senderId, String body, Instant createdAt) {
}
