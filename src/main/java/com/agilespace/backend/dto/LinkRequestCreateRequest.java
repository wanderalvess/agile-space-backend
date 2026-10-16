package com.agilespace.backend.dto;

/** Corpo de POST /api/link-requests. {@code memberId} e {@code message} são opcionais. */
public record LinkRequestCreateRequest(String projectKey, String memberId, String emailInJira, String message) {
}
