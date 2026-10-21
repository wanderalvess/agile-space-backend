package com.agilespace.backend.repository;

import com.agilespace.backend.domain.Prompt;
import com.agilespace.backend.dto.PromptFacetsDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Agregações (facets) da Biblioteca de IA sobre a mesma Specification da listagem. */
@Repository
public class PromptQueryRepository {

    @PersistenceContext
    private EntityManager em;

    public Map<String, Long> countByType(Specification<Prompt> spec) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> q = cb.createTupleQuery();
        Root<Prompt> r = q.from(Prompt.class);
        q.multiselect(r.get("type").alias("t"), cb.count(r).alias("n"));
        where(q, spec, r, cb);
        q.groupBy(r.get("type"));
        Map<String, Long> out = new LinkedHashMap<>();
        for (Tuple t : em.createQuery(q).getResultList()) {
            String type = t.get("t", String.class);
            out.merge(type == null || type.isBlank() ? "prompt" : type, t.get("n", Long.class), Long::sum);
        }
        return out;
    }

    public List<PromptFacetsDto.TagCount> topTags(Specification<Prompt> spec, int limit) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> q = cb.createTupleQuery();
        Root<Prompt> r = q.from(Prompt.class);
        Join<Prompt, String> tag = r.join("tags");
        q.multiselect(tag.alias("tag"), cb.countDistinct(r).alias("n"));
        where(q, spec, r, cb);
        q.groupBy(tag);
        q.orderBy(cb.desc(cb.countDistinct(r)), cb.asc(tag));
        List<PromptFacetsDto.TagCount> out = new ArrayList<>();
        for (Tuple t : em.createQuery(q).setMaxResults(limit).getResultList()) {
            out.add(new PromptFacetsDto.TagCount(t.get("tag", String.class), t.get("n", Long.class)));
        }
        return out;
    }

    public long sumUseCount(Specification<Prompt> spec) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> q = cb.createQuery(Long.class);
        Root<Prompt> r = q.from(Prompt.class);
        q.select(cb.sumAsLong(cb.coalesce(r.<Integer>get("useCount"), 0)));
        where(q, spec, r, cb);
        Long v = em.createQuery(q).getSingleResult();
        return v == null ? 0 : v;
    }

    private static void where(CriteriaQuery<?> q, Specification<Prompt> spec, Root<Prompt> r, CriteriaBuilder cb) {
        Predicate p = spec.toPredicate(r, q, cb);
        if (p != null) q.where(p);
    }
}
