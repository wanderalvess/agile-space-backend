package com.agilespace.backend.dto;

import java.util.List;

/** Contrato da busca por trechos e do preenchimento de embeddings (/api/v1/knowledge/...). */
public final class KnowledgeSearchDtos {

    private KnowledgeSearchDtos() {
    }

    /**
     * query: texto livre (parte textual). embedding: vetor da pergunta (384 dims, normalizado, mesmo modelo dos
     * trechos), opcional (parte semântica). Pelo menos um dos dois. Filtros opcionais por tipo, origem e módulo.
     */
    public record SearchRequest(String query, List<Double> embedding, Integer k, String docType, String source,
                                String module) {
    }

    /** rank* é a posição (1 = melhor) em cada lista; nulo se o trecho não apareceu nela. */
    public record Hit(String chunkId, String documentId, String docType, String externalId, String title,
                      String headingPath, String text, String sourceUrl, String source, String module,
                      String version, double score, Integer textRank, Integer vectorRank) {
    }

    /** mode: hybrid, text ou vector, conforme o que foi possível usar. */
    public record SearchResponse(String mode, List<Hit> hits) {
    }

    /** input é exatamente o texto que deve virar embedding (título + caminho + trecho). */
    public record PendingChunk(String chunkId, String documentId, String input) {
    }

    public record EmbeddingFill(String chunkId, List<Double> embedding) {
    }

    public record FillRequest(List<EmbeddingFill> items) {
    }

    public record FillResponse(int updated, List<String> rejected) {
    }

    /** more=true: ainda há documentos sem trechos; chame de novo. */
    public record BackfillResponse(int documents, int chunks, boolean more) {
    }
}
