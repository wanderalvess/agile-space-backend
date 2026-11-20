package com.agilespace.backend.dto;

/** Contrato: docs/home-api.md §2. {@code messages} = mensagens diretas não lidas. */
public record UnreadCountDto(long forYou, long messages, long announcements, long total) {

    public static UnreadCountDto of(long forYou, long messages, long announcements) {
        return new UnreadCountDto(forYou, messages, announcements, forYou + messages + announcements);
    }
}
