package com.agilespace.backend.dto;

import java.util.List;

/** Contrato da ingestão em lote da Base de Conhecimento (/api/v1/knowledge/ingest). */
public final class KnowledgeIngestDtos {

    private KnowledgeIngestDtos() {
    }

    /**
     * Um documento a gravar. (docType, externalId) é a chave do upsert. {@code related} lista referências
     * "docType:externalId" de outros documentos (as que ainda não existem voltam como aviso).
     * {@code embedding} é opcional (384 dims, normalizado): sem ele o documento fica sem busca semântica
     * até o embedding no servidor existir.
     */
    public record Item(
            String docType,
            String externalId,
            String title,
            String content,
            String source,
            String sourceUrl,
            String module,
            String version,
            String category,
            List<String> tags,
            List<String> related,
            String visibility,
            List<Double> embedding) {
    }

    public record Request(List<Item> items) {
    }

    /** status: created, updated, unchanged ou error. */
    public record ItemResult(String docType, String externalId, String status, String id, String message,
                             List<String> warnings) {
    }

    public record Response(int created, int updated, int unchanged, int errors, List<ItemResult> results) {
    }

    public record ManifestEntry(String docType, String externalId, String contentHash, String version, String id) {
    }
}
