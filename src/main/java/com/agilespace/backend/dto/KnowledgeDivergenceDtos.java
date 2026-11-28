package com.agilespace.backend.dto;

import java.util.List;

/** Contrato de GET /api/v1/knowledge/divergences. */
public final class KnowledgeDivergenceDtos {

    private KnowledgeDivergenceDtos() {
    }

    public record DocRef(String id, String externalId, String title, String sourceUrl, String version, String module) {
    }

    /**
     * Comparação de um doc de código com a página do TDN ligada a ele (related). Compara identificadores
     * (rotas, nomes de classe/método/campo, tabelas), não o sentido do texto. similarity é Jaccard entre os
     * dois conjuntos. status: aligned (>= 0.7), review (0.3 a 0.7) ou diverges (< 0.3). onlyIn* limitados a 30.
     */
    public record Pair(DocRef code, DocRef tdn, int shared, double similarity, String status,
                       List<String> onlyInCode, List<String> onlyInTdn) {
    }

    /**
     * pairs: do mais divergente para o menos. unlinkedCode: externalId de docs de código sem página de TDN
     * ligada (nada a comparar; limitado a 50, total em unlinkedCodeCount).
     */
    public record Report(int pairCount, int diverging, int review, int aligned, List<Pair> pairs,
                         int unlinkedCodeCount, List<String> unlinkedCode) {
    }
}
