package com.agilespace.backend.dto;

/** Quem esteve na Review quando ela foi finalizada (resumo). O nome é o gravado no momento da finalização. */
public record ShowcaseParticipantDto(String userId, String displayName) {
}
