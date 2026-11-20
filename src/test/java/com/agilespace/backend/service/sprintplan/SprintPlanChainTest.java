package com.agilespace.backend.service.sprintplan;

import com.agilespace.backend.service.sprintplan.SprintPlanChain.Level;
import com.agilespace.backend.service.sprintplan.SprintPlanChain.Node;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SprintPlanChain - Codificação, Code Review, QA e cascata em dias úteis")
class SprintPlanChainTest {

    static LocalDate d(int day) {
        return LocalDate.of(2026, 11, day);
    }

    static Node n(String key, Level l, Integer s, Integer e) {
        return new Node(key, l, s == null ? null : d(s), e == null ? null : d(e));
    }

    @Test
    @DisplayName("minStart: CR segue a Codificação; QA segue o CR; Codificação não tem mínimo")
    void minStartPerLevel() {
        Node cod = n("COD", Level.CODING, 2, 4);
        Node cr = n("CR", Level.CODE_REVIEW, 4, 5);
        Node qa = n("QA", Level.QA, 5, 6);
        List<Node> all = List.of(cod, cr, qa);
        assertNull(SprintPlanChain.minStart(cod, all));
        assertEquals(d(4), SprintPlanChain.minStart(cr, all));
        assertEquals("COD", SprintPlanChain.startsAfterKey(cr, all));
        assertEquals(d(5), SprintPlanChain.minStart(qa, all));
        assertEquals("CR", SprintPlanChain.startsAfterKey(qa, all));
    }

    @Test
    @DisplayName("QA sem Code Review datado segue o fim da Codificação")
    void qaFallsBackToCoding() {
        Node cod = n("COD", Level.CODING, 2, 4);
        Node qa = n("QA", Level.QA, 5, 6);
        assertEquals(d(4), SprintPlanChain.minStart(qa, List.of(cod, qa)));
        assertEquals("COD", SprintPlanChain.startsAfterKey(qa, List.of(cod, qa)));
    }

    @Test
    @DisplayName("antecessoras sem data não travam ninguém")
    void undatedPredecessorsDoNotConstrain() {
        Node cod = n("COD", Level.CODING, null, null);
        Node cr = n("CR", Level.CODE_REVIEW, 4, 5);
        assertNull(SprintPlanChain.minStart(cr, List.of(cod, cr)));
        assertEquals("COD", SprintPlanChain.startsAfterKey(cr, List.of(cod, cr)));
    }

    @Test
    @DisplayName("fim da Codificação adia 2 dias úteis: CR e QA acompanham, pulando o fim de semana, com a duração preservada")
    void cascadeForward() {
        Node cod = n("COD", Level.CODING, 2, 4);
        Node cr = n("CR", Level.CODE_REVIEW, 4, 5);
        Node qa = n("QA", Level.QA, 5, 6);
        List<Node> changed = SprintPlanChain.applyWithCascade(List.of(cod, cr, qa), cod, d(2), d(6), Set.of());
        assertEquals(3, changed.size());
        assertEquals(d(6), cr.start);
        assertEquals(d(9), cr.end);
        assertEquals(d(9), qa.start);
        assertEquals(d(10), qa.end);
    }

    @Test
    @DisplayName("fim da Codificação antecipa 1 dia útil: CR e QA voltam 1 dia útil (outro sentido)")
    void cascadeBackward() {
        Node cod = n("COD", Level.CODING, 2, 4);
        Node cr = n("CR", Level.CODE_REVIEW, 4, 5);
        Node qa = n("QA", Level.QA, 5, 6);
        SprintPlanChain.applyWithCascade(List.of(cod, cr, qa), cod, d(2), d(3), Set.of());
        assertEquals(d(3), cr.start);
        assertEquals(d(4), cr.end);
        assertEquals(d(4), qa.start);
        assertEquals(d(5), qa.end);
    }

    @Test
    @DisplayName("mexer no fim do CR só desloca o QA; a Codificação fica")
    void editingCodeReviewMovesOnlyQa() {
        Node cod = n("COD", Level.CODING, 2, 4);
        Node cr = n("CR", Level.CODE_REVIEW, 4, 5);
        Node qa = n("QA", Level.QA, 5, 6);
        SprintPlanChain.applyWithCascade(List.of(cod, cr, qa), cr, d(4), d(6), Set.of());
        assertEquals(d(4), cod.end);
        assertEquals(d(6), qa.start);
        assertEquals(d(9), qa.end);
    }

    @Test
    @DisplayName("sem Code Review, o QA acompanha a Codificação")
    void qaFollowsCodingWithoutCodeReview() {
        Node cod = n("COD", Level.CODING, 2, 4);
        Node qa = n("QA", Level.QA, 5, 6);
        SprintPlanChain.applyWithCascade(List.of(cod, qa), cod, d(2), d(5), Set.of());
        assertEquals(d(6), qa.start);
        assertEquals(d(9), qa.end);
    }

    @Test
    @DisplayName("Codificação que não é a última a terminar não mexe em ninguém")
    void nonCriticalCodingDoesNotCascade() {
        Node c1 = n("C1", Level.CODING, 2, 6);
        Node c2 = n("C2", Level.CODING, 2, 3);
        Node cr = n("CR", Level.CODE_REVIEW, 6, 6);
        List<Node> changed = SprintPlanChain.applyWithCascade(List.of(c1, c2, cr), c2, d(2), d(4), Set.of());
        assertEquals(1, changed.size());
        assertEquals(d(6), cr.start);
    }

    @Test
    @DisplayName("feriado não conta como dia útil no delta nem no deslocamento")
    void holidaysAreSkipped() {
        Set<LocalDate> hol = Set.of(d(5));
        assertEquals(1, SprintPlanChain.workingDelta(d(4), d(6), hol));
        assertEquals(d(6), SprintPlanChain.shift(d(4), 1, hol));
        assertEquals(d(4), SprintPlanChain.shift(d(6), -1, hol));
        assertEquals(-1, SprintPlanChain.workingDelta(d(9), d(6), Set.of()));
    }

    @Test
    @DisplayName("nós sem data não são deslocados")
    void undatedDependentsStay() {
        Node cod = n("COD", Level.CODING, 2, 4);
        Node cr = n("CR", Level.CODE_REVIEW, null, null);
        SprintPlanChain.applyWithCascade(List.of(cod, cr), cod, d(2), d(6), Set.of());
        assertNull(cr.start);
    }
}
