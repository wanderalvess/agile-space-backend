package com.agilespace.backend.service;

import com.agilespace.backend.domain.KnowledgeDocRelation;
import com.agilespace.backend.domain.KnowledgeDocument;
import com.agilespace.backend.dto.KnowledgeDivergenceDtos.DocRef;
import com.agilespace.backend.dto.KnowledgeDivergenceDtos.Pair;
import com.agilespace.backend.dto.KnowledgeDivergenceDtos.Report;
import com.agilespace.backend.repository.KnowledgeDocRelationRepository;
import com.agilespace.backend.repository.KnowledgeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Divergências código x TDN sem LLM: para cada par ligado por related (um doc source=code e um source=tdn),
 * extrai identificadores técnicos de cada lado e compara os conjuntos. Barato, determinístico e verificável;
 * não entende o sentido do texto. Para julgar o que realmente diverge, use o contexto do /ask com
 * mixedSources=true e um LLM sobre os pares de menor similaridade.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeDivergenceService {

    static final int MAX_LIST = 30;
    static final int MAX_UNLINKED = 50;
    static final int MAX_IDENTIFIERS = 2000;
    static final double ALIGNED_MIN = 0.7;
    static final double REVIEW_MIN = 0.3;
    static final Set<String> LISTED = Set.of("published", "indexed");

    // /pedidos/{id}, /api/v1/itens, :id, {id}: caminho de rota (começa com /, tem letra, sem espaços)
    private static final Pattern ROUTE = Pattern.compile("(?<![\\w/:.])/[A-Za-z][A-Za-z0-9_\\-{}:.]*(?:/[A-Za-z0-9_\\-{}:.]+)*");
    // `algo` em crase
    private static final Pattern BACKTICK = Pattern.compile("`([^`\\n]{2,80})`");
    // PascalCase/camelCase com ao menos uma maiúscula interna: OrderService, createOrder
    private static final Pattern CAMEL = Pattern.compile("\\b[A-Za-z][a-z0-9]+(?:[A-Z][A-Za-z0-9]*)+\\b");
    // snake_case com ao menos um underscore: tabelas e colunas
    private static final Pattern SNAKE = Pattern.compile("\\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\\b");
    private static final Pattern PATH_PARAM = Pattern.compile("\\{[^}/]*}|:[A-Za-z_][A-Za-z0-9_]*");

    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeDocRelationRepository relationRepository;

    @Transactional(readOnly = true)
    public Report report(String module, Integer limit) {
        int max = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        String mod = module == null || module.isBlank() ? null : module.trim();

        List<KnowledgeDocRelation> relations = relationRepository.findAll();
        Set<UUID> ids = new HashSet<>();
        relations.forEach(r -> {
            ids.add(r.getDocumentId());
            ids.add(r.getRelatedDocumentId());
        });
        Map<UUID, KnowledgeDocument> docs = knowledgeRepository.findAllById(ids).stream()
                .filter(d -> LISTED.contains(d.getStatus()))
                .collect(Collectors.toMap(KnowledgeDocument::getId, d -> d));

        Set<String> seen = new HashSet<>();
        Set<UUID> linkedCode = new HashSet<>();
        List<Pair> pairs = new ArrayList<>();
        for (KnowledgeDocRelation r : relations) {
            KnowledgeDocument a = docs.get(r.getDocumentId());
            KnowledgeDocument b = docs.get(r.getRelatedDocumentId());
            if (a == null || b == null) continue;
            KnowledgeDocument code = "code".equals(a.getSource()) ? a : "code".equals(b.getSource()) ? b : null;
            KnowledgeDocument tdn = "tdn".equals(a.getSource()) ? a : "tdn".equals(b.getSource()) ? b : null;
            if (code == null || tdn == null || code == tdn) continue;
            if (mod != null && !mod.equals(code.getModuleName())) continue;
            linkedCode.add(code.getId());
            if (!seen.add(code.getId() + "|" + tdn.getId())) continue;
            pairs.add(compare(code, tdn));
        }
        pairs.sort(Comparator.comparingDouble(Pair::similarity).thenComparing(p -> p.code().externalId()));

        int diverging = (int) pairs.stream().filter(p -> "diverges".equals(p.status())).count();
        int review = (int) pairs.stream().filter(p -> "review".equals(p.status())).count();
        int aligned = pairs.size() - diverging - review;

        List<String> unlinked = knowledgeRepository.findBySourceAndStatusIn("code", LISTED).stream()
                .filter(d -> mod == null || mod.equals(d.getModuleName()))
                .filter(d -> !linkedCode.contains(d.getId()))
                .map(KnowledgeDocument::getExternalId).filter(Objects::nonNull).sorted().toList();
        return new Report(pairs.size(), diverging, review, aligned, pairs.stream().limit(max).toList(),
                unlinked.size(), unlinked.stream().limit(MAX_UNLINKED).toList());
    }

    Pair compare(KnowledgeDocument code, KnowledgeDocument tdn) {
        Set<String> c = identifiers(code.getContent());
        Set<String> t = identifiers(tdn.getContent());
        Set<String> shared = new TreeSet<>(c);
        shared.retainAll(t);
        Set<String> onlyCode = new TreeSet<>(c);
        onlyCode.removeAll(t);
        Set<String> onlyTdn = new TreeSet<>(t);
        onlyTdn.removeAll(c);
        int union = shared.size() + onlyCode.size() + onlyTdn.size();
        double sim = union == 0 ? 1.0 : (double) shared.size() / union;
        return new Pair(ref(code), ref(tdn), shared.size(), Math.round(sim * 1000) / 1000.0, status(sim),
                onlyCode.stream().limit(MAX_LIST).toList(), onlyTdn.stream().limit(MAX_LIST).toList());
    }

    static String status(double similarity) {
        return similarity >= ALIGNED_MIN ? "aligned" : similarity >= REVIEW_MIN ? "review" : "diverges";
    }

    /** Identificadores técnicos do texto, normalizados (minúsculas; parâmetros de rota viram {}). */
    static Set<String> identifiers(String text) {
        Set<String> out = new TreeSet<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        collect(ROUTE.matcher(text), 0, true, out);
        collect(BACKTICK.matcher(text), 1, false, out);
        collect(CAMEL.matcher(text), 0, false, out);
        collect(SNAKE.matcher(text), 0, false, out);
        return out;
    }

    private static void collect(Matcher m, int group, boolean route, Set<String> out) {
        while (m.find() && out.size() < MAX_IDENTIFIERS) {
            String s = m.group(group).trim();
            if (route) {
                s = PATH_PARAM.matcher(s).replaceAll("{}");
                s = s.replaceAll("[.:]+$", "");
                if (s.length() < 3) continue;
            }
            s = s.toLowerCase();
            if (s.length() >= 2 && s.length() <= 120) {
                out.add(s);
            }
        }
    }

    private static DocRef ref(KnowledgeDocument d) {
        return new DocRef(d.getId().toString(), d.getExternalId(), d.getTitle(), d.getSourceUrl(), d.getDocVersion(),
                d.getModuleName());
    }
}
