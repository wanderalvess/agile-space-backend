package com.agilespace.backend.dto;

import java.time.Instant;

/** Contrato: docs/home-api.md §3. */
public record ConversationDto(String id, UserRefDto otherUser, MessageDto lastMessage, long unreadCount, Instant updatedAt) {
}
