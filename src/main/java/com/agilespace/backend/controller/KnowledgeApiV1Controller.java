package com.agilespace.backend.controller;

import com.agilespace.backend.domain.KnowledgeDocument;
import com.agilespace.backend.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * API pública de leitura da Base de Conhecimento, autenticada por API key
 * (ApiKeyAuthenticationFilter, header X-Api-Key) em vez de JWT de sessão —
 * pensada pra chamada de máquina/serviço (ex: servidor MCP). Reaproveita o
 * KnowledgeService existente (mesma lógica de busca por texto do endpoint
 * /api/knowledge já usado pela UI), sem nenhum filtro de squad/tenant — os
 * documentos da KB não têm esse conceito.
 */
@RestController
@RequestMapping("/api/v1/knowledge/docs")
@RequiredArgsConstructor
public class KnowledgeApiV1Controller {

    private final KnowledgeService knowledgeService;

    @GetMapping
    public ResponseEntity<Page<KnowledgeDocument>> listDocuments(
            @RequestParam(value = "q", required = false) String query,
            @PageableDefault(size = 20) Pageable pageable) {
        // status=null: listDocuments já exclui "deleted" por padrão nesse caso (ver KnowledgeService).
        return ResponseEntity.ok(knowledgeService.listDocuments(query, null, null, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<KnowledgeDocument> getDocumentById(@PathVariable("id") UUID id) {
        try {
            return ResponseEntity.ok(knowledgeService.getDocumentById(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
