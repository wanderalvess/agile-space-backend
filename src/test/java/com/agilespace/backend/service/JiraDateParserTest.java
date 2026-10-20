package com.agilespace.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JiraDateParserTest {

    @Test
    void isoComOffsetSemDoisPontosViraComDoisPontos() {
        assertEquals("2026-10-20T09:00:00-03:00", JiraDateParser.normalize("2026-10-20T09:00:00.000-0300"));
    }

    @Test
    void isoComOffsetComDoisPontosEhMantido() {
        assertEquals("2026-10-20T09:00:00-03:00", JiraDateParser.normalize("2026-10-20T09:00:00-03:00"));
    }

    @Test
    void isoComZViraOffsetZero() {
        assertEquals("2026-10-20T12:00:00+00:00", JiraDateParser.normalize("2026-10-20T12:00:00Z"));
    }

    @Test
    void somenteDataEhMantida() {
        assertEquals("2026-10-20", JiraDateParser.normalize("2026-10-20"));
    }

    @Test
    void dataHoraSemOffsetUsaFusoDoBrasil() {
        assertEquals("2026-10-20T09:00:00-03:00", JiraDateParser.normalize("2026-10-20T09:00:00"));
    }

    @Test
    void vazioNuloEIrreconhecivelViramVazio() {
        assertEquals("", JiraDateParser.normalize(null));
        assertEquals("", JiraDateParser.normalize("   "));
        assertEquals("", JiraDateParser.normalize("ABC-123"));
        assertEquals("", JiraDateParser.normalize("20/10/26 9:00 AM"));
    }

    @Test
    void textoComDataEHoraQuebradaCaiParaSoAData() {
        assertEquals("2026-10-20", JiraDateParser.normalize("2026-10-20T??"));
    }
}
