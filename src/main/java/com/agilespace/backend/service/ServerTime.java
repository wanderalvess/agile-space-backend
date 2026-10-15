package com.agilespace.backend.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Conversão entre os LocalDateTime gravados pelo servidor ({@code LocalDateTime.now()} da JVM, que roda em UTC em
 * produção) e os Instant dos DTOs da home. Usa o fuso da JVM nos dois sentidos, então também fica certo numa JVM
 * local fora de UTC.
 */
final class ServerTime {

    private ServerTime() {
    }

    static LocalDateTime now() {
        return LocalDateTime.now();
    }

    static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }

    static LocalDateTime toLocal(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneId.systemDefault());
    }
}
