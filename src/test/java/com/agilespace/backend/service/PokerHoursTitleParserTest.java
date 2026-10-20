package com.agilespace.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Espelha os casos de {@code formatEstimateCopy} do frontend (src/lib/__tests__/poker-copy.test.ts): todo texto que
 * o "Copiar estimativa" do Poker gera precisa ser lido de volta aqui.
 */
@DisplayName("PokerHoursTitleParser - horas do título \"(DEV xh / QA yh)\"")
class PokerHoursTitleParserTest {

    private static void assertHours(String title, String dev, String qa) {
        PokerHoursTitleParser.TitleHours h = PokerHoursTitleParser.parse(title);
        assertNotNull(h, "esperava horas em: " + title);
        assertEquals(0, new BigDecimal(dev).compareTo(h.dev()), "DEV de " + title);
        assertEquals(0, new BigDecimal(qa).compareTo(h.qa()), "QA de " + title);
    }

    @Test
    @DisplayName("front: '(DEV 10h / QA 4h / UX 2h)' na ordem canônica -> DEV 10, QA 4 (UX ignorado)")
    void canonicalOrderWithUx() {
        assertHours("Tela de login (DEV 10h / QA 4h / UX 2h)", "10", "4");
    }

    @Test
    @DisplayName("front: '(DEV 8h / QA 0h)' e, com skipZero, '(DEV 8h)' -> QA 0")
    void zeroAndSkipZero() {
        assertHours("(DEV 8h / QA 0h)", "8", "0");
        assertHours("Ajuste (DEV 8h)", "8", "0");
    }

    @Test
    @DisplayName("front: baralho de pontos '(DEV 5 SP / QA 3 SP)' não é hora -> null; texto vazio -> null")
    void storyPointsAndEmpty() {
        assertNull(PokerHoursTitleParser.parse("Item (DEV 5 SP / QA 3 SP)"));
        assertNull(PokerHoursTitleParser.parse(""));
        assertNull(PokerHoursTitleParser.parse(null));
    }

    @Test
    @DisplayName("front: papel fora da lista no fim '(QA 2h / ARQUITETO 3h)' -> DEV 0, QA 2")
    void extraRoleAtTheEnd() {
        assertHours("Migração (QA 2h / ARQUITETO 3h)", "0", "2");
    }

    @Test
    @DisplayName("decimais com vírgula ou ponto, caixa e espaços variados")
    void decimalsAndCase() {
        assertHours("X (dev 2,5h/qa 1.5h)", "2.5", "1.5");
        assertHours("X ( DEV 3 h / QA 1 h )", "3", "1");
    }

    @Test
    @DisplayName("título sem padrão, só UX, ou outros parênteses -> null; vale o último grupo com horas")
    void noPatternOrLastGroupWins() {
        assertNull(PokerHoursTitleParser.parse("Corrigir bug (urgente)"));
        assertNull(PokerHoursTitleParser.parse("Protótipo (UX 4h)"));
        assertHours("Relatório (v2) (DEV 1h / QA 1h) (DEV 6h / QA 2h)", "6", "2");
        assertHours("Relatório (cliente X) (DEV 6h / QA 2h)", "6", "2");
    }
}
