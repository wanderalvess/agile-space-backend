package com.agilespace.backend.service;

import com.agilespace.backend.domain.EmbeddingConverter;
import com.agilespace.backend.domain.KnowledgeChunk;
import com.agilespace.backend.domain.KnowledgeDocument;
import com.agilespace.backend.dto.KnowledgeSearchDtos.*;
import com.agilespace.backend.repository.KnowledgeChunkRepository;
import com.agilespace.backend.repository.KnowledgeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Trechos da Base de Conhecimento: reconstrução ao ingerir, fila de embeddings pendentes e busca híbrida
 * (texto Postgres + similaridade vetorial, combinadas por RRF).
 * <p>
 * O embedding NÃO é gerado aqui: quem tem o modelo (o Hub ou o frontend, com
 * paraphrase-multilingual-MiniLM-L12-v2, 384 dims, normalizado) busca os pendentes, calcula e devolve. Sem
 * embeddings a busca funciona só por texto. A similaridade é feita em memória (produto escalar): suficiente
 * para alguns milhares de trechos; trocar por pgvector depois não muda o contrato.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeChunkService {

    /** Dimensão do modelo de embedding em uso; vetores de outro tamanho são recusados. */
    public static final int EMBEDDING_DIMS = 384;
    public static final int MAX_PENDING_BATCH = 200;
    static final int DEFAULT_K = 8;
    static final int MAX_K = 50;
    static final int RRF_K = 60;
    static final int CANDIDATES = 60;
    static final float MIN_SIMILARITY = 0.2f;
    static final Set<String> LISTED = Set.of("published", "indexed");

    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeRepository knowledgeRepository;
    private final NamedParameterJdbcTemplate jdbc;

    // ---------- Reconstrução ----------

    /**
     * Regrava os trechos do documento. Trecho com o mesmo caminho+texto de um já existente herda o embedding
     * (não precisa recalcular quando só outra seção mudou).
     */
    @Transactional
    public int rebuild(KnowledgeDocument doc) {
        List<KnowledgeChunker.Chunk> fresh = KnowledgeChunker.chunk(doc.getContent());
        Map<String, float[]> reusable = new HashMap<>();
        for (KnowledgeChunk old : chunkRepository.findByDocumentIdOrderByOrdinalAsc(doc.getId())) {
            if (old.getEmbedding() != null) {
                reusable.putIfAbsent(old.getTextHash(), old.getEmbedding());
            }
        }
        chunkRepository.deleteAllByDocumentId(doc.getId());
        List<KnowledgeChunk> toSave = new ArrayList<>();
        for (KnowledgeChunker.Chunk c : fresh) {
            String h = textHash(c.headingPath(), c.text());
            toSave.add(KnowledgeChunk.builder().documentId(doc.getId()).ordinal(c.ordinal())
                    .headingPath(blankToNull(c.headingPath())).text(c.text()).textHash(h)
                    .embedding(reusable.get(h)).build());
        }
        chunkRepository.saveAll(toSave);
        return toSave.size();
    }

    @Transactional
    public void remove(UUID documentId) {
        chunkRepository.deleteAllByDocumentId(documentId);
    }

    // ---------- Embeddings pendentes ----------

    @Transactional(readOnly = true)
    public List<PendingChunk> pending(Integer limit) {
        int n = limit == null ? 100 : Math.max(1, Math.min(limit, MAX_PENDING_BATCH));
        List<KnowledgeChunk> chunks = chunkRepository.findPending(PageRequest.of(0, n));
        Map<UUID, KnowledgeDocument> docs = knowledgeRepository
                .findAllById(chunks.stream().map(KnowledgeChunk::getDocumentId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(KnowledgeDocument::getId, d -> d));
        return chunks.stream().map(c -> {
            KnowledgeDocument d = docs.get(c.getDocumentId());
            return new PendingChunk(c.getId().toString(), c.getDocumentId().toString(),
                    embeddingInput(d == null ? null : d.getTitle(), c.getHeadingPath(), c.getText()));
        }).toList();
    }

    /** Texto que deve virar embedding: título, caminho da seção e o trecho. */
    public static String embeddingInput(String title, String headingPath, String text) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) {
            sb.append(title.trim()).append('\n');
        }
        if (headingPath != null && !headingPath.isBlank()) {
            sb.append(headingPath.trim()).append('\n');
        }
        return sb.append(text).toString();
    }

    @Transactional
    public FillResponse fill(List<EmbeddingFill> items) {
        if (items == null || items.isEmpty()) {
            throw bad("Informe 'items'.");
        }
        if (items.size() > MAX_PENDING_BATCH) {
            throw bad("No máximo " + MAX_PENDING_BATCH + " embeddings por requisição.");
        }
        List<String> rejected = new ArrayList<>();
        Map<UUID, float[]> valid = new LinkedHashMap<>();
        for (EmbeddingFill it : items) {
            UUID id = parseUuid(it == null ? null : it.chunkId());
            float[] vec = id == null ? null : toVector(it.embedding());
            if (id == null || vec == null) {
                rejected.add(it == null ? "null" : String.valueOf(it.chunkId()));
            } else {
                valid.put(id, vec);
            }
        }
        int updated = 0;
        for (KnowledgeChunk c : chunkRepository.findByIdIn(valid.keySet())) {
            c.setEmbedding(valid.remove(c.getId()));
            updated++;
        }
        valid.keySet().forEach(id -> rejected.add(id.toString()));
        return new FillResponse(updated, rejected);
    }

    // ---------- Busca ----------

    @Transactional(readOnly = true)
    public SearchResponse search(SearchRequest req) {
        if (req == null) {
            throw bad("Corpo obrigatório.");
        }
        int k = req.k() == null ? DEFAULT_K : Math.max(1, Math.min(req.k(), MAX_K));
        String tsq = toTsQuery(req.query());
        float[] qvec = req.embedding() == null || req.embedding().isEmpty() ? null : toVector(req.embedding());
        if (req.embedding() != null && !req.embedding().isEmpty() && qvec == null) {
            throw bad("embedding deve ter " + EMBEDDING_DIMS + " números.");
        }
        if (tsq == null && qvec == null) {
            throw bad("Informe 'query' (texto) e/ou 'embedding'.");
        }
        Filter f = new Filter(req.docType(), req.source(), req.module());

        List<UUID> textRanked = tsq == null ? List.of() : textSearch(tsq, f);
        List<UUID> vectorRanked = qvec == null ? List.of() : vectorSearch(qvec, f);
        String mode = tsq != null && qvec != null ? "hybrid" : tsq != null ? "text" : "vector";

        List<Fused> fused = fuse(textRanked, vectorRanked, k);
        return new SearchResponse(mode, loadHits(fused));
    }

    record Fused(UUID id, double score, Integer textRank, Integer vectorRank) {
    }

    /** Reciprocal Rank Fusion: soma 1/(60+posição) de cada lista em que o trecho aparece. */
    static List<Fused> fuse(List<UUID> textRanked, List<UUID> vectorRanked, int k) {
        Map<UUID, Integer> tr = new HashMap<>();
        Map<UUID, Integer> vr = new HashMap<>();
        for (int i = 0; i < textRanked.size(); i++) {
            tr.putIfAbsent(textRanked.get(i), i + 1);
        }
        for (int i = 0; i < vectorRanked.size(); i++) {
            vr.putIfAbsent(vectorRanked.get(i), i + 1);
        }
        Set<UUID> all = new LinkedHashSet<>(textRanked);
        all.addAll(vectorRanked);
        return all.stream()
                .map(id -> {
                    double s = 0;
                    if (tr.containsKey(id)) s += 1.0 / (RRF_K + tr.get(id));
                    if (vr.containsKey(id)) s += 1.0 / (RRF_K + vr.get(id));
                    return new Fused(id, s, tr.get(id), vr.get(id));
                })
                .sorted(Comparator.comparingDouble(Fused::score).reversed())
                .limit(k)
                .toList();
    }

    /** Palavras da pergunta unidas por OR (websearch_to_tsquery): recall alto, o ts_rank_cd ordena. */
    static String toTsQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        Set<String> words = new LinkedHashSet<>();
        for (String w : query.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 2 && words.size() < 20) {
                words.add(w);
            }
        }
        return words.isEmpty() ? null : String.join(" or ", words);
    }

    private record Filter(String docType, String source, String module) {
    }

    private String where(Filter f, MapSqlParameterSource p) {
        StringBuilder sb = new StringBuilder(" d.status IN ('published','indexed')");
        if (blankToNull(f.docType()) != null) {
            sb.append(" AND d.doc_type = :docType");
            p.addValue("docType", f.docType().trim());
        }
        if (blankToNull(f.source()) != null) {
            sb.append(" AND d.source = :source");
            p.addValue("source", f.source().trim());
        }
        if (blankToNull(f.module()) != null) {
            sb.append(" AND d.module_name = :module");
            p.addValue("module", f.module().trim());
        }
        return sb.toString();
    }

    private List<UUID> textSearch(String tsq, Filter f) {
        MapSqlParameterSource p = new MapSqlParameterSource("tsq", tsq).addValue("lim", CANDIDATES);
        String sql = "SELECT c.id FROM knowledge_chunk c JOIN knowledge_kb d ON d.id = c.document_id, "
                + "websearch_to_tsquery('portuguese', :tsq) AS q(query) "
                + "WHERE c.tsv @@ q.query AND" + where(f, p)
                + " ORDER BY ts_rank_cd(c.tsv, q.query) DESC, c.id LIMIT :lim";
        return jdbc.query(sql, p, (rs, i) -> rs.getObject("id", UUID.class));
    }

    private List<UUID> vectorSearch(float[] q, Filter f) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String sql = "SELECT c.id, c.embedding FROM knowledge_chunk c JOIN knowledge_kb d ON d.id = c.document_id "
                + "WHERE c.embedding IS NOT NULL AND" + where(f, p);
        EmbeddingConverter conv = new EmbeddingConverter();
        List<Map.Entry<UUID, Float>> scored = new ArrayList<>();
        jdbc.query(sql, p, rs -> {
            float[] v = conv.convertToEntityAttribute(rs.getString("embedding"));
            if (v != null && v.length == q.length) {
                float s = dot(v, q);
                if (s >= MIN_SIMILARITY) {
                    scored.add(Map.entry(rs.getObject("id", UUID.class), s));
                }
            }
        });
        scored.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));
        return scored.stream().limit(CANDIDATES).map(Map.Entry::getKey).toList();
    }

    private List<Hit> loadHits(List<Fused> fused) {
        if (fused.isEmpty()) {
            return List.of();
        }
        Map<UUID, Hit> byId = new HashMap<>();
        Map<UUID, Fused> meta = fused.stream().collect(Collectors.toMap(Fused::id, x -> x));
        String sql = "SELECT c.id, c.document_id, c.heading_path, c.text, d.doc_type, d.external_id, d.title, "
                + "d.source_url, d.source, d.module_name, d.doc_version "
                + "FROM knowledge_chunk c JOIN knowledge_kb d ON d.id = c.document_id WHERE c.id IN (:ids)";
        jdbc.query(sql, new MapSqlParameterSource("ids", meta.keySet()), rs -> {
            UUID id = rs.getObject("id", UUID.class);
            Fused fz = meta.get(id);
            byId.put(id, new Hit(id.toString(), rs.getString("document_id"), rs.getString("doc_type"),
                    rs.getString("external_id"), rs.getString("title"), rs.getString("heading_path"),
                    rs.getString("text"), rs.getString("source_url"), rs.getString("source"),
                    rs.getString("module_name"), rs.getString("doc_version"), fz.score(), fz.textRank(),
                    fz.vectorRank()));
        });
        return fused.stream().map(x -> byId.get(x.id())).filter(Objects::nonNull).toList();
    }

    // ---------- Utilidades ----------

    static float dot(float[] a, float[] b) {
        float s = 0f;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    /** Vetor válido: exatamente EMBEDDING_DIMS números finitos. */
    static float[] toVector(List<Double> raw) {
        if (raw == null || raw.size() != EMBEDDING_DIMS) {
            return null;
        }
        float[] out = new float[raw.size()];
        for (int i = 0; i < out.length; i++) {
            Double d = raw.get(i);
            if (d == null || d.isNaN() || d.isInfinite()) {
                return null;
            }
            out[i] = d.floatValue();
        }
        return out;
    }

    static String textHash(String headingPath, String text) {
        try {
            String s = (headingPath == null ? "" : headingPath) + "\n" + text;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static UUID parseUuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
