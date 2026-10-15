package com.agilespace.backend.service;

import com.agilespace.backend.service.SessionStatusResolver.SessionStatus;
import com.agilespace.backend.service.SessionStatusResolver.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static com.agilespace.backend.service.SessionStatusResolver.SessionStatus.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SessionStatusResolver - regras de status por ferramenta")
class SessionStatusResolverTest {

    private final SessionStatusResolver resolver = new SessionStatusResolver();
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    private static Instant ago(long hours) {
        return NOW.minus(Duration.ofHours(hours));
    }

    private static Instant minutesAgo(long minutes) {
        return NOW.minus(Duration.ofMinutes(minutes));
    }

    @Test
    @DisplayName("Poker: encerrada > assíncrona > iniciada (LIVE) > planejamento")
    void poker() {
        assertEquals(FINISHED, resolver.poker("sync", ago(2), ago(1), null, NOW));
        assertEquals(FINISHED, resolver.poker("async", null, ago(1), null, NOW), "encerrada vale mesmo assíncrona");
        assertEquals(ASYNC_OPEN, resolver.poker("async", null, null, null, NOW));
        assertEquals(ASYNC_OPEN, resolver.poker("ASYNC", ago(30), null, null, NOW), "assíncrona não sofre a regra das 12 h");
        assertEquals(LIVE, resolver.poker("sync", ago(1), null, null, NOW));
        assertEquals(PLANNING, resolver.poker("sync", null, null, null, NOW));
        assertEquals(PLANNING, resolver.poker(null, null, null, null, NOW));
    }

    @Test
    @DisplayName("Poker: LIVE sem atividade há mais de 12 h vira FINISHED; heartbeat recente mantém LIVE")
    void pokerStale() {
        assertEquals(FINISHED, resolver.poker("sync", ago(13), null, null, NOW));
        assertEquals(LIVE, resolver.poker("sync", ago(11), null, null, NOW));
        assertEquals(LIVE, resolver.poker("sync", ago(20), null, minutesAgo(5), NOW));
        assertEquals(FINISHED, resolver.poker("sync", ago(20), null, ago(13), NOW));
    }

    @Test
    @DisplayName("Showcase: planning, active, finished; active abandonada vira FINISHED")
    void showcase() {
        assertEquals(PLANNING, resolver.showcase("planning", ago(1), NOW));
        assertEquals(PLANNING, resolver.showcase("qualquer-outro", ago(1), NOW));
        assertEquals(PLANNING, resolver.showcase(null, null, NOW));
        assertEquals(LIVE, resolver.showcase("active", ago(2), NOW));
        assertEquals(LIVE, resolver.showcase("ACTIVE", ago(11), NOW));
        assertEquals(FINISHED, resolver.showcase("active", ago(13), NOW));
        assertEquals(FINISHED, resolver.showcase("active", null, NOW));
        assertEquals(FINISHED, resolver.showcase("finished", minutesAgo(1), NOW));
    }

    @Test
    @DisplayName("Health Check: coletando = ASYNC_OPEN (sem regra de 12 h), finalizado = FINISHED")
    void healthCheck() {
        assertEquals(ASYNC_OPEN, resolver.healthCheck("collecting"));
        assertEquals(ASYNC_OPEN, resolver.healthCheck(null));
        assertEquals(FINISHED, resolver.healthCheck("finished"));
        assertEquals(FINISHED, resolver.healthCheck("FINISHED"));
    }

    @Test
    @DisplayName("Retro e Brainstorming: LIVE com atividade nas últimas 3 h, senão FINISHED")
    void boards() {
        for (boolean retro : new boolean[]{true, false}) {
            assertEquals(LIVE, retro ? resolver.retro(minutesAgo(5), NOW) : resolver.brainstorming(minutesAgo(5), NOW));
            assertEquals(LIVE, retro ? resolver.retro(ago(2), NOW) : resolver.brainstorming(ago(2), NOW));
            assertEquals(FINISHED, retro ? resolver.retro(ago(4), NOW) : resolver.brainstorming(ago(4), NOW));
            assertEquals(FINISHED, retro ? resolver.retro(null, NOW) : resolver.brainstorming(null, NOW));
        }
    }

    @Test
    @DisplayName("Datas em texto: ISO com Z, sem fuso, epoch em ms, data pura e lixo")
    void parseTime() {
        assertEquals(Instant.parse("2026-10-15T10:00:00Z"), SessionStatusResolver.parseTime("2026-10-15T10:00:00.000Z"));
        assertEquals(Instant.parse("2026-10-15T13:00:00Z"), SessionStatusResolver.parseTime("2026-10-15T10:00:00-03:00"));
        assertEquals(Instant.parse("2026-10-15T10:00:00Z"), SessionStatusResolver.parseTime("2026-10-15T10:00:00"));
        assertEquals(Instant.ofEpochMilli(1_760_000_000_000L), SessionStatusResolver.parseTime("1760000000000"));
        assertEquals(Instant.parse("2026-10-15T00:00:00Z"), SessionStatusResolver.parseTime("2026-10-15"));
        assertNull(SessionStatusResolver.parseTime("ontem"));
        assertNull(SessionStatusResolver.parseTime(" "));
        assertNull(SessionStatusResolver.parseTime(null));
    }

    @Test
    @DisplayName("Ferramenta: caminho em minúsculas, links e ordem dos status")
    void toolsAndLinks() {
        assertEquals(Tool.HEALTH_CHECK, Tool.fromPath("health-check").orElseThrow());
        assertEquals(Tool.POKER, Tool.fromPath("POKER").orElseThrow());
        assertTrue(Tool.fromPath("xyz").isEmpty());
        assertEquals("/room/r1", Tool.POKER.link("r1"));
        assertEquals("/retro/b1", Tool.RETRO.link("b1"));
        assertEquals("/showcase/s1", Tool.SHOWCASE.link("s1"));
        assertEquals("/health-check/h1", Tool.HEALTH_CHECK.link("h1"));
        assertEquals("/brainstorming/x", Tool.BRAINSTORMING.link("x"));
        assertTrue(SessionStatus.LIVE.ordinal() < SessionStatus.ASYNC_OPEN.ordinal());
        assertTrue(SessionStatus.ASYNC_OPEN.ordinal() < SessionStatus.PLANNING.ordinal());
    }
}
