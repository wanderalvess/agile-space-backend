package com.agilespace.backend.dto;

import java.time.LocalDateTime;

/** Quem está apresentando a Review agora e o que está mostrando (docs/ceremony-review-api.md). Efêmero, em memória. */
public record ShowcasePresenterDto(String userId, String displayName, String cardId, String step, LocalDateTime presentingAt) {
}
