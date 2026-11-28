package com.agilespace.backend.dto;

import java.util.List;

/** Contrato do /api/v1/knowledge/ask (RAG: monta o contexto citável; o LLM é de quem chama). */
public final class KnowledgeAskDtos {

    private KnowledgeAskDtos() {
    }

    /**
     * provider: hoje só "none" (padrão): o servidor recupera os trechos e devolve o prompt pronto, sem chamar
     * nenhum LLM; quem chama (ex.: o Hub com Copilot) gera a resposta. embedding é o vetor da pergunta
     * (384 dims), opcional: com ele a busca é híbrida.
     */
    public record AskRequest(String question, List<Double> embedding, Integer k, String docType, String source,
                             String module, String provider) {
    }

    /** n é o número que aparece como [n] no contexto e que a resposta deve citar. */
    public record Citation(int n, String chunkId, String documentId, String docType, String externalId, String title,
                           String headingPath, String sourceUrl, String source, String module, String version) {
    }

    /**
     * answerable=false quando nada foi recuperado (o chamador deve dizer que não encontrou, não inventar).
     * mixedSources=true quando há trechos de código e de TDN juntos: vale pedir a comparação (divergências).
     */
    public record AskResponse(String provider, String mode, boolean answerable, boolean mixedSources,
                              String systemPrompt, String userPrompt, List<Citation> citations) {
    }
}
