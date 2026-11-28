package com.agilespace.backend.controller;

import com.agilespace.backend.domain.ApiKeyScope;
import com.agilespace.backend.dto.KnowledgeSearchDtos.FillRequest;
import com.agilespace.backend.dto.KnowledgeSearchDtos.FillResponse;
import com.agilespace.backend.dto.KnowledgeSearchDtos.PendingChunk;
import com.agilespace.backend.dto.KnowledgeSearchDtos.SearchRequest;
import com.agilespace.backend.dto.KnowledgeSearchDtos.SearchResponse;
import com.agilespace.backend.security.ApiKeyAccess;
import com.agilespace.backend.service.KnowledgeChunkService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Busca híbrida por trechos e fila de embeddings, por API key. Buscar exige KNOWLEDGE_READ; ler os pendentes e
 * gravar embeddings exigem KNOWLEDGE_WRITE.
 */
@RestController
@RequestMapping("/api/v1/knowledge")
@RequiredArgsConstructor
public class KnowledgeSearchApiV1Controller {

    private final KnowledgeChunkService chunkService;

    /** Texto (Postgres, português) e/ou vetor da pergunta; devolve trechos com origem para citação. */
    @PostMapping("/search")
    public ResponseEntity<SearchResponse> search(@RequestBody SearchRequest body, HttpServletRequest request) {
        ApiKeyAccess.requireScope(request, ApiKeyScope.KNOWLEDGE_READ);
        return ResponseEntity.ok(chunkService.search(body));
    }

    /** Trechos sem embedding: o cliente calcula sobre 'input' e devolve em PUT /chunks/embeddings. */
    @GetMapping("/chunks/pending")
    public ResponseEntity<List<PendingChunk>> pending(@RequestParam(value = "limit", required = false) Integer limit,
                                                      HttpServletRequest request) {
        ApiKeyAccess.requireScope(request, ApiKeyScope.KNOWLEDGE_WRITE);
        return ResponseEntity.ok(chunkService.pending(limit));
    }

    @PutMapping("/chunks/embeddings")
    public ResponseEntity<FillResponse> fill(@RequestBody FillRequest body, HttpServletRequest request) {
        ApiKeyAccess.requireScope(request, ApiKeyScope.KNOWLEDGE_WRITE);
        return ResponseEntity.ok(chunkService.fill(body == null ? null : body.items()));
    }
}
