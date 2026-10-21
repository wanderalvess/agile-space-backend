package com.agilespace.backend.repository;

import com.agilespace.backend.domain.Prompt;
import com.agilespace.backend.domain.PromptFavorite;
import com.agilespace.backend.service.PromptCaller;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Filtros da Biblioteca de IA como Specification. Quem pode ver o quê é decidido aqui (e só aqui)
 * para lista, facets e totais: visitante só público; membro público + os próprios; ADMIN tudo.
 */
public final class PromptSpecs {

    private static final String PUBLIC = "public";
    private static final String ARCHIVED = "archived";
    private static final String FROM = "áàâãäéèêëíìîïóòôõöúùûüçñ";
    private static final String TO = "aaaaaeeeeiiiiooooouuuucn";
    private static final int MAX_TOKENS = 8;
    private static final String[] TEXT_FIELDS =
            {"title", "description", "content", "authorName", "businessGoal", "targetAudience"};

    private PromptSpecs() {}

    /** Minúsculas e sem acento, igual ao que o banco faz com translate(lower(coluna)). */
    public static String fold(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    private static String likePattern(String token) {
        return "%" + token.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static Expression<String> folded(CriteriaBuilder cb, Expression<String> e) {
        return cb.function("translate", String.class, cb.lower(e), cb.literal(FROM), cb.literal(TO));
    }

    /** Itens que o chamador (null = visitante) pode ver. */
    public static Specification<Prompt> visibleTo(PromptCaller caller) {
        return (root, q, cb) -> {
            if (caller != null && caller.isAdmin()) return cb.conjunction();
            Predicate isPublic = cb.equal(root.get("visibility"), PUBLIC);
            if (caller == null || caller.id() == null) return isPublic;
            return cb.or(isPublic, cb.equal(root.get("authorId"), caller.id()));
        };
    }

    public static Specification<Prompt> filtered(PromptFilter f, PromptCaller caller) {
        return (root, q, cb) -> {
            List<Predicate> all = new ArrayList<>();
            all.add(visibleTo(caller).toPredicate(root, q, cb));

            String visibility = f.visibility() == null ? "all" : f.visibility().trim().toLowerCase(Locale.ROOT);
            if (PUBLIC.equals(visibility)) {
                all.add(cb.equal(root.get("visibility"), PUBLIC));
            } else if ("private".equals(visibility)) {
                if (caller == null || caller.id() == null) {
                    all.add(cb.disjunction());
                } else {
                    all.add(cb.or(cb.isNull(root.get("visibility")), cb.notEqual(root.get("visibility"), PUBLIC)));
                    all.add(cb.equal(root.get("authorId"), caller.id()));
                }
            }

            if (hasText(f.authorId())) all.add(cb.equal(root.get("authorId"), f.authorId().trim()));
            if (hasText(f.type())) all.add(cb.equal(cb.lower(root.<String>get("type")), f.type().trim().toLowerCase(Locale.ROOT)));
            if (hasText(f.impact())) all.add(cb.equal(cb.lower(root.<String>get("impact")), f.impact().trim().toLowerCase(Locale.ROOT)));

            String status = f.status() == null ? "" : f.status().trim().toLowerCase(Locale.ROOT);
            if (status.isEmpty()) {
                all.add(cb.or(cb.isNull(root.get("status")), cb.notEqual(cb.lower(root.<String>get("status")), ARCHIVED)));
            } else if (!"all".equals(status)) {
                all.add(cb.equal(cb.lower(root.<String>get("status")), status));
            }

            if (hasText(f.tag())) {
                Subquery<Integer> sq = q.subquery(Integer.class);
                Root<Prompt> r2 = sq.correlate(root);
                Join<Prompt, String> tags = r2.join("tags");
                sq.select(cb.literal(1)).where(cb.equal(cb.lower(tags), f.tag().trim().toLowerCase(Locale.ROOT).replace("#", "")));
                all.add(cb.exists(sq));
            }

            if (f.favorites()) {
                if (caller == null || caller.id() == null) {
                    all.add(cb.disjunction());
                } else {
                    Subquery<Integer> sq = q.subquery(Integer.class);
                    Root<PromptFavorite> fav = sq.from(PromptFavorite.class);
                    sq.select(cb.literal(1)).where(
                            cb.equal(fav.get("promptId"), root.get("id")),
                            cb.equal(fav.get("userId"), caller.id()));
                    all.add(cb.exists(sq));
                }
            }

            if (hasText(f.query())) {
                String[] tokens = fold(f.query()).trim().split("\\s+");
                for (int i = 0; i < tokens.length && i < MAX_TOKENS; i++) {
                    if (tokens[i].isEmpty()) continue;
                    all.add(tokenMatches(root, q, cb, likePattern(tokens[i])));
                }
            }
            return cb.and(all.toArray(new Predicate[0]));
        };
    }

    private static Predicate tokenMatches(Root<Prompt> root, CriteriaQuery<?> q, CriteriaBuilder cb, String pattern) {
        List<Predicate> any = new ArrayList<>();
        for (String field : TEXT_FIELDS) {
            any.add(cb.like(folded(cb, root.<String>get(field)), pattern, '\\'));
        }
        Subquery<Integer> sq = q.subquery(Integer.class);
        Root<Prompt> r2 = sq.correlate(root);
        Join<Prompt, String> tags = r2.join("tags");
        sq.select(cb.literal(1)).where(cb.like(folded(cb, tags), pattern, '\\'));
        any.add(cb.exists(sq));
        return cb.or(any.toArray(new Predicate[0]));
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
