package com.agilespace.backend.service;

import com.agilespace.backend.dto.KnowledgeAskDtos.AskRequest;
import com.agilespace.backend.dto.KnowledgeAskDtos.AskResponse;
import com.agilespace.backend.dto.KnowledgeAskDtos.Citation;
import com.agilespace.backend.dto.KnowledgeSearchDtos.Hit;
import com.agilespace.backend.dto.KnowledgeSearchDtos.SearchRequest;
import com.agilespace.backend.dto.KnowledgeSearchDtos.SearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG sem LLM no servidor: recupera os trechos (busca híbrida) e devolve o prompt pronto com fontes numeradas.
 * Quem chama gera a resposta com o LLM que tiver (Copilot, Gemini, Claude) e cita as fontes por [n]. Um provedor
 * de LLM no servidor pode ser acrescentado depois sem mudar este contrato (campo provider).
 */
@Service
@RequiredArgsConstructor
public class KnowledgeAskService {

    static final String PROVIDER_NONE = "none";
    static final int DEFAULT_K = 6;
    static final int MAX_CONTEXT_CHARS = 12_000;
    static final int MAX_QUESTION_CHARS = 2_000;

    static final String SYSTEM_PROMPT = """
            Você é o assistente da base de conhecimento técnica (documentação gerada do código das APIs Java e \
            páginas do TDN). Responda em português, somente com o que está nas FONTES abaixo.
            1. Cite as fontes no texto como [1], [2] logo após cada afirmação que dependa delas.
            2. Se as fontes não trouxerem a resposta, diga claramente que não encontrou na base; não invente nomes \
            de classes, rotas, tabelas ou parâmetros.
            3. Se fontes de código e de TDN divergirem, diga o que cada uma afirma, cite as duas e aponte a \
            divergência em vez de escolher uma em silêncio.
            4. Seja direto: comece pela resposta e só depois detalhe.""";

    private final KnowledgeChunkService chunkService;

    public AskResponse ask(AskRequest req) {
        if (req == null || req.question() == null || req.question().isBlank()) {
            throw bad("Informe 'question'.");
        }
        String question = req.question().trim();
        if (question.length() > MAX_QUESTION_CHARS) {
            throw bad("A pergunta passa do limite de " + MAX_QUESTION_CHARS + " caracteres.");
        }
        String provider = req.provider() == null || req.provider().isBlank() ? PROVIDER_NONE
                : req.provider().trim().toLowerCase();
        if (!PROVIDER_NONE.equals(provider)) {
            throw bad("provider '" + provider + "' não está configurado neste servidor; use none "
                    + "(o servidor devolve o contexto e o prompt, e você gera a resposta).");
        }
        SearchResponse found = chunkService.search(new SearchRequest(question, req.embedding(),
                req.k() == null ? DEFAULT_K : req.k(), req.docType(), req.source(), req.module()));

        List<Citation> citations = new ArrayList<>();
        StringBuilder ctx = new StringBuilder();
        boolean hasCode = false;
        boolean hasTdn = false;
        for (Hit h : found.hits()) {
            String block = formatBlock(citations.size() + 1, h);
            if (ctx.length() + block.length() > MAX_CONTEXT_CHARS && !citations.isEmpty()) {
                break;
            }
            ctx.append(block);
            citations.add(new Citation(citations.size() + 1, h.chunkId(), h.documentId(), h.docType(), h.externalId(),
                    h.title(), h.headingPath(), h.sourceUrl(), h.source(), h.module(), h.version()));
            hasCode |= "code".equals(h.source());
            hasTdn |= "tdn".equals(h.source());
        }
        boolean answerable = !citations.isEmpty();
        String userPrompt = answerable
                ? "FONTES:\n\n" + ctx + "PERGUNTA: " + question
                : "FONTES:\n\n(nenhuma fonte encontrada)\n\nPERGUNTA: " + question;
        return new AskResponse(provider, found.mode(), answerable, hasCode && hasTdn, SYSTEM_PROMPT, userPrompt,
                citations);
    }

    static String formatBlock(int n, Hit h) {
        StringBuilder sb = new StringBuilder("[").append(n).append("] ").append(nz(h.title()));
        if (h.headingPath() != null && !h.headingPath().isBlank()) {
            sb.append(" > ").append(h.headingPath());
        }
        List<String> meta = new ArrayList<>();
        if (h.source() != null) meta.add("origem: " + h.source());
        if (h.module() != null) meta.add("módulo: " + h.module());
        if (h.version() != null) meta.add("versão: " + h.version());
        if (h.sourceUrl() != null) meta.add(h.sourceUrl());
        if (!meta.isEmpty()) {
            sb.append(" (").append(String.join("; ", meta)).append(')');
        }
        return sb.append('\n').append(h.text()).append("\n\n").toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
