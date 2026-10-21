package com.agilespace.backend.repository;

import com.agilespace.backend.domain.Prompt;
import com.agilespace.backend.service.PromptCaller;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifica a regra de visibilidade/filtros construída em PromptSpecs com a Criteria API simulada.
 * As consultas reais (Hibernate + Postgres) não rodam aqui: só a lógica de quais predicados entram.
 */
class PromptSpecsTest {

    private CriteriaBuilder cb;
    private Root<Prompt> root;
    private CriteriaQuery<?> query;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        cb = mock(CriteriaBuilder.class, RETURNS_MOCKS);
        root = mock(Root.class, RETURNS_MOCKS);
        query = mock(CriteriaQuery.class, RETURNS_MOCKS);
    }

    private PromptFilter filter(String visibility, String status, boolean favorites, String q) {
        return new PromptFilter(q, null, visibility, status, null, null, favorites, null);
    }

    @Test
    void foldRemovesAccentsAndCase() {
        assertEquals("acao e coracao", PromptSpecs.fold("AÇÃO e Coração"));
        assertEquals("", PromptSpecs.fold(null));
    }

    @Test
    void adminSeesEverythingWithoutAuthorRestriction() {
        PromptSpecs.visibleTo(new PromptCaller("adm", "ADMIN")).toPredicate(root, query, cb);
        verify(cb).conjunction();
        verify(cb, never()).equal(any(), eq("adm"));
    }

    @Test
    void memberSeesPublicOrOwn() {
        PromptSpecs.visibleTo(new PromptCaller("u1", "MEMBER")).toPredicate(root, query, cb);
        verify(cb).equal(any(), eq("public"));
        verify(cb).equal(any(), eq("u1"));
        verify(cb).or(any(), any());
    }

    @Test
    void visitorSeesOnlyPublic() {
        PromptSpecs.visibleTo(null).toPredicate(root, query, cb);
        verify(cb).equal(any(), eq("public"));
        verify(cb, never()).or(any(), any());
    }

    @Test
    void privateFilterForVisitorMatchesNothing() {
        PromptSpecs.filtered(filter("private", null, false, null), null).toPredicate(root, query, cb);
        verify(cb).disjunction();
    }

    @Test
    void privateFilterRestrictsToOwnItems() {
        PromptSpecs.filtered(filter("private", null, false, null), new PromptCaller("u1", "MEMBER")).toPredicate(root, query, cb);
        verify(cb, atLeastOnce()).notEqual(any(), eq("public"));
        verify(cb, atLeastOnce()).equal(any(), eq("u1"));
    }

    @Test
    void favoritesForVisitorMatchesNothing() {
        PromptSpecs.filtered(filter(null, "all", true, null), null).toPredicate(root, query, cb);
        verify(cb).disjunction();
    }

    @Test
    void defaultStatusExcludesArchivedAndAllDoesNot() {
        PromptSpecs.filtered(filter(null, null, false, null), null).toPredicate(root, query, cb);
        verify(cb).notEqual(any(), eq("archived"));

        CriteriaBuilder cb2 = mock(CriteriaBuilder.class, RETURNS_MOCKS);
        PromptSpecs.filtered(filter(null, "all", false, null), null).toPredicate(root, query, cb2);
        verify(cb2, never()).notEqual(any(), eq("archived"));
        verify(cb2, never()).equal(any(), eq("all"));
    }

    @Test
    void textQueryIsFoldedAndEscapedPerToken() {
        PromptSpecs.filtered(filter(null, "all", false, "Ação 100%"), null).toPredicate(root, query, cb);
        verify(cb, atLeastOnce()).like(any(Expression.class), eq("%acao%"), eq('\\'));
        verify(cb, atLeastOnce()).like(any(Expression.class), eq("%100\\%%"), eq('\\'));
    }

    @Test
    void tagFilterIsLowercasedWithoutHash() {
        PromptFilter f = new PromptFilter(null, null, null, "all", null, "#Scrum", false, null);
        PromptSpecs.filtered(f, null).toPredicate(root, query, cb);
        verify(cb).equal(any(), eq("scrum"));
        verify(cb, never()).equal(any(), eq("#scrum"));
    }
}
