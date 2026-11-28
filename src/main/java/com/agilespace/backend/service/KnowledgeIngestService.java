package com.agilespace.backend.service;

import com.agilespace.backend.domain.KnowledgeDocRelation;
import com.agilespace.backend.domain.KnowledgeDocument;
import com.agilespace.backend.dto.KnowledgeIngestDtos.ItemResult;
import com.agilespace.backend.dto.KnowledgeIngestDtos.Item;
import com.agilespace.backend.dto.KnowledgeIngestDtos.ManifestEntry;
import com.agilespace.backend.dto.KnowledgeIngestDtos.Response;
import com.agilespace.backend.repository.KnowledgeDocRelationRepository;
import com.agilespace.backend.repository.KnowledgeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Ingestão em lote de documentos externos (docs de API, páginas do TDN, prompts, skills, instructions) na
 * Base de Conhecimento, com upsert idempotente por (docType, externalId). Reaproveita a tabela, o histórico de
 * versões e as validações do {@link KnowledgeService}. O hash do conteúdo é calculado AQUI (nunca confiado ao
 * cliente): item igual ao já gravado não é regravado.
 * <p>
 * Ainda não gera chunks nem embedding (fase seguinte). Se o conteúdo mudar e o item não trouxer embedding, o
 * embedding antigo é descartado (ficaria desatualizado), como já ocorre na reimportação do TDN.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeIngestService {

    public static final int MAX_BATCH = 50;
    static final int MAX_EXTERNAL_ID = 255;
    static final Set<String> DOC_TYPES = Set.of("api_doc", "tdn_page", "prompt", "skill", "instruction");
    static final Set<String> SOURCES = Set.of("code", "tdn");
    static final String KIND_RELATED = "related";

    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeDocRelationRepository relationRepository;
    private final KnowledgeService knowledgeService;

    @Transactional(readOnly = true)
    public List<ManifestEntry> manifest(String docType) {
        String type = blankToNull(docType);
        if (type != null && !DOC_TYPES.contains(type)) {
            throw bad("docType inválido: " + type);
        }
        return knowledgeRepository.findManifest(type).stream()
                .map(d -> new ManifestEntry(d.getDocType(), d.getExternalId(), d.getContentHash(), d.getDocVersion(),
                        d.getId().toString()))
                .toList();
    }

    @Transactional
    public Response ingest(List<Item> items, String actorId) {
        if (items == null || items.isEmpty()) {
            throw bad("Informe 'items' com ao menos um documento.");
        }
        if (items.size() > MAX_BATCH) {
            throw bad("No máximo " + MAX_BATCH + " documentos por requisição.");
        }
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int errors = 0;
        // Relações só depois do lote inteiro: um item pode apontar para outro do mesmo lote.
        Map<UUID, List<String>> pendingRelations = new LinkedHashMap<>();
        List<ItemResult> results = new ArrayList<>();
        Map<UUID, Integer> indexById = new HashMap<>();
        for (Item item : items) {
            try {
                ItemResult r = ingestOne(item, actorId, pendingRelations);
                switch (r.status()) {
                    case "created" -> created++;
                    case "updated" -> updated++;
                    default -> unchanged++;
                }
                indexById.put(UUID.fromString(r.id()), results.size());
                results.add(r);
            } catch (ResponseStatusException e) {
                errors++;
                results.add(new ItemResult(item == null ? null : item.docType(), item == null ? null : item.externalId(),
                        "error", null, e.getReason(), List.of()));
            }
        }
        pendingRelations.forEach((docId, refs) -> {
            List<String> missing = applyRelations(docId, refs);
            Integer idx = indexById.get(docId);
            if (!missing.isEmpty() && idx != null) {
                ItemResult old = results.get(idx);
                List<String> warnings = new ArrayList<>(old.warnings());
                missing.forEach(m -> warnings.add("related não encontrado: " + m));
                results.set(idx, new ItemResult(old.docType(), old.externalId(), old.status(), old.id(),
                        old.message(), warnings));
            }
        });
        return new Response(created, updated, unchanged, errors, results);
    }

    /** Lixeira (soft delete) por id estável. Devolve false se não existir ou já estiver apagado. */
    @Transactional
    public boolean delete(String docType, String externalId, String actorId) {
        String type = blankToNull(docType);
        if (type == null || !DOC_TYPES.contains(type)) {
            throw bad("docType inválido.");
        }
        String ext = blankToNull(externalId);
        if (ext == null) {
            throw bad("externalId é obrigatório.");
        }
        Optional<KnowledgeDocument> opt = knowledgeRepository.findByDocTypeAndExternalId(type, ext);
        if (opt.isEmpty() || "deleted".equals(opt.get().getStatus())) {
            return false;
        }
        KnowledgeDocument doc = opt.get();
        doc.setStatus("deleted");
        doc.setDeletedAt(LocalDateTime.now(ZoneOffset.UTC));
        doc.setDeletedBy(actorId);
        knowledgeRepository.save(doc);
        return true;
    }

    private ItemResult ingestOne(Item item, String actorId, Map<UUID, List<String>> pendingRelations) {
        if (item == null) {
            throw bad("Item vazio.");
        }
        String docType = blankToNull(item.docType());
        if (docType == null || !DOC_TYPES.contains(docType)) {
            throw bad("docType inválido. Use um de " + new TreeSet<>(DOC_TYPES) + ".");
        }
        String externalId = blankToNull(item.externalId());
        if (externalId == null || externalId.length() > MAX_EXTERNAL_ID) {
            throw bad("externalId é obrigatório e vai até " + MAX_EXTERNAL_ID + " caracteres.");
        }
        String source = blankToNull(item.source());
        if (source != null && !SOURCES.contains(source)) {
            throw bad("source inválido. Use code ou tdn.");
        }
        String visibility = blankToNull(item.visibility()) == null ? "shared" : item.visibility().trim();
        if (!"shared".equals(visibility)) {
            // A coluna existe, mas listas, buscas e MCP ainda não filtram por ela: aceitar 'restricted' agora
            // daria falsa sensação de proteção. Liberar quando o filtro estiver em todos os caminhos de leitura.
            throw bad("visibility '" + visibility + "' ainda não é suportada; use shared.");
        }
        String title = item.title() == null ? "" : item.title().trim();
        String content = item.content() == null ? "" : item.content();
        String category = blankToNull(item.category()) == null ? "Geral" : item.category().trim();
        Set<String> tags = new TreeSet<>();
        if (item.tags() != null) {
            item.tags().stream().filter(t -> t != null && !t.isBlank()).map(String::trim).forEach(tags::add);
        }
        float[] embedding = toEmbedding(item.embedding());

        // Valida com as mesmas regras (título, tamanhos, tags) da criação pela UI.
        KnowledgeDocument probe = KnowledgeDocument.builder().title(title).content(content).category(category)
                .fullPath(category).moduleName(blankToNull(item.module())).status("published")
                .tags(new HashSet<>(tags)).embedding(embedding).authorId(actorId).build();
        KnowledgeService.validateDocument(probe);
        if (item.sourceUrl() != null && item.sourceUrl().length() > 2000) {
            throw bad("sourceUrl passa do limite de 2000 caracteres.");
        }
        if (item.version() != null && item.version().length() > 100) {
            throw bad("version passa do limite de 100 caracteres.");
        }

        String hash = hash(docType, title, content, category, source, item.sourceUrl(), item.module(), item.version(),
                tags, visibility);
        Optional<KnowledgeDocument> existingOpt = knowledgeRepository.findByDocTypeAndExternalId(docType, externalId);
        if (existingOpt.isPresent()) {
            KnowledgeDocument existing = existingOpt.get();
            boolean wasDeleted = "deleted".equals(existing.getStatus());
            if (!wasDeleted && hash.equals(existing.getContentHash())) {
                // Mesmo conteúdo: só aceita embedding novo se o documento ainda não tinha.
                if (embedding != null && existing.getEmbedding() == null) {
                    existing.setEmbedding(embedding);
                    knowledgeRepository.save(existing);
                }
                queueRelations(existing.getId(), item, pendingRelations);
                return new ItemResult(docType, externalId, "unchanged", existing.getId().toString(), null, List.of());
            }
            boolean changed = wasDeleted || knowledgeService.contentChanged(existing, probe);
            if (changed) {
                knowledgeService.ensureBaseline(existing);
            }
            apply(existing, docType, externalId, title, content, category, source, item, tags, hash, actorId);
            existing.setStatus("published");
            existing.setDeletedAt(null);
            existing.setDeletedBy(null);
            if (embedding != null) {
                existing.setEmbedding(embedding);
            } else if (changed) {
                existing.setEmbedding(null);
            }
            KnowledgeDocument saved = knowledgeRepository.save(existing);
            if (changed) {
                knowledgeService.recordVersion(saved, actorId, LocalDateTime.now(ZoneOffset.UTC));
            }
            queueRelations(saved.getId(), item, pendingRelations);
            return new ItemResult(docType, externalId, "updated", saved.getId().toString(), null, List.of());
        }

        KnowledgeDocument doc = KnowledgeDocument.builder().authorId(actorId).views(0).build();
        apply(doc, docType, externalId, title, content, category, source, item, tags, hash, actorId);
        doc.setStatus("published");
        doc.setEmbedding(embedding);
        doc.setSquadId(knowledgeService.squadOfUser(actorId));
        KnowledgeDocument saved = knowledgeRepository.save(doc);
        knowledgeService.recordVersion(saved, actorId, LocalDateTime.now(ZoneOffset.UTC));
        queueRelations(saved.getId(), item, pendingRelations);
        return new ItemResult(docType, externalId, "created", saved.getId().toString(), null, List.of());
    }

    private void apply(KnowledgeDocument doc, String docType, String externalId, String title, String content,
                       String category, String source, Item item, Set<String> tags, String hash, String actorId) {
        doc.setDocType(docType);
        doc.setExternalId(externalId);
        doc.setTitle(title);
        doc.setContent(content);
        doc.setCategory(category);
        doc.setFullPath(category);
        doc.setModuleName(blankToNull(item.module()));
        doc.setModuleId(blankToNull(item.module()));
        doc.setSource(source);
        doc.setSourceUrl(blankToNull(item.sourceUrl()));
        doc.setDocVersion(blankToNull(item.version()));
        doc.setContentHash(hash);
        doc.setVisibility("shared");
        doc.setTags(new HashSet<>(tags));
        doc.setByteSize((long) content.getBytes(StandardCharsets.UTF_8).length);
        doc.setUpdatedBy(actorId);
    }

    private void queueRelations(UUID docId, Item item, Map<UUID, List<String>> pending) {
        if (item.related() != null) {
            pending.put(docId, item.related());
        }
    }

    /** Regrava as ligações 'related' do documento; devolve as referências que não existem (ainda). */
    private List<String> applyRelations(UUID docId, List<String> refs) {
        relationRepository.deleteByDocumentIdAndKind(docId, KIND_RELATED);
        relationRepository.flush();
        List<String> missing = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (String ref : refs) {
            if (ref == null || ref.isBlank()) {
                continue;
            }
            int sep = ref.indexOf(':');
            Optional<KnowledgeDocument> target = sep <= 0 ? Optional.empty()
                    : knowledgeRepository.findByDocTypeAndExternalId(ref.substring(0, sep), ref.substring(sep + 1));
            if (target.isEmpty() || "deleted".equals(target.get().getStatus()) || target.get().getId().equals(docId)) {
                missing.add(ref);
                continue;
            }
            if (seen.add(target.get().getId())) {
                relationRepository.save(KnowledgeDocRelation.builder().documentId(docId)
                        .relatedDocumentId(target.get().getId()).kind(KIND_RELATED).build());
            }
        }
        return missing;
    }

    private static float[] toEmbedding(List<Double> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        if (raw.size() > KnowledgeService.MAX_EMBEDDING_DIMS) {
            throw bad("embedding passa de " + KnowledgeService.MAX_EMBEDDING_DIMS + " posições.");
        }
        float[] out = new float[raw.size()];
        for (int i = 0; i < out.length; i++) {
            if (raw.get(i) == null) {
                throw bad("embedding só aceita números.");
            }
            out[i] = raw.get(i).floatValue();
        }
        return out;
    }

    static String hash(String docType, String title, String content, String category, String source, String sourceUrl,
                       String module, String version, Set<String> tags, String visibility) {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[]{docType, title, content, category, source, sourceUrl, module, version,
                String.join(",", new TreeSet<>(tags)), visibility}) {
            String p = part == null ? "" : part;
            sb.append(p.length()).append(':').append(p).append('|');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
