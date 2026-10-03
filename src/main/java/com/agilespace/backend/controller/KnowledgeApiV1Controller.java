package com.agilespace.backend.controller;

import com.agilespace.backend.domain.ApiKeyScope;
import com.agilespace.backend.domain.KnowledgeDocument;
import com.agilespace.backend.security.ApiKeyAccess;
import com.agilespace.backend.service.KnowledgeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * API pública de leitura e criação da Base de Conhecimento, autenticada por API key
 * (ApiKeyAuthenticationFilter, header X-Api-Key) em vez de JWT de sessão —
 * pensada pra chamada de máquina/serviço (ex: servidor MCP). Reaproveita o
 * KnowledgeService existente (mesma lógica de busca por texto do endpoint
 * /api/knowledge já usado pela UI), sem nenhum filtro de squad/tenant — os
 * documentos da KB não têm esse conceito. Escopo por chave (ApiKeyAccess) —
 * fase 3 do plano de API key com escopo.
 */
@RestController
@RequestMapping("/api/v1/knowledge/docs")
@RequiredArgsConstructor
public class KnowledgeApiV1Controller {

    private final KnowledgeService knowledgeService;

    @GetMapping
    public ResponseEntity<Page<KnowledgeDocument>> listDocuments(
            @RequestParam(value = "q", required = false) String query,
            @PageableDefault(size = 20) Pageable pageable,
            HttpServletRequest request) {
        ApiKeyAccess.requireScope(request, ApiKeyScope.KNOWLEDGE_READ);
        // status=null: listDocuments já exclui "deleted" por padrão nesse caso (ver KnowledgeService).
        return ResponseEntity.ok(knowledgeService.listDocuments(query, null, null, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<KnowledgeDocument> getDocumentById(@PathVariable("id") UUID id, HttpServletRequest request) {
        ApiKeyAccess.requireScope(request, ApiKeyScope.KNOWLEDGE_READ);
        try {
            return ResponseEntity.ok(knowledgeService.getDocumentById(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping
    public ResponseEntity<KnowledgeDocument> createDocument(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        ApiKeyAccess.requireScope(request, ApiKeyScope.KNOWLEDGE_WRITE);
        String title = (String) payload.get("title");
        if (title == null || title.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        String content = payload.get("content") != null ? (String) payload.get("content") : "";
        String category = payload.get("category") != null ? (String) payload.get("category") : "Geral";

        Set<String> tagSet = new HashSet<>();
        Object tagsObj = payload.get("tags");
        if (tagsObj instanceof List<?> list) {
            for (Object t : list) {
                if (t != null && !t.toString().isBlank()) {
                    tagSet.add(t.toString().trim());
                }
            }
        } else if (tagsObj instanceof String str) {
            for (String t : str.split(",")) {
                if (!t.trim().isEmpty()) {
                    tagSet.add(t.trim());
                }
            }
        }

        KnowledgeDocument doc = KnowledgeDocument.builder()
                .title(title.trim())
                .content(content)
                .category(category)
                .fullPath(category)
                .status("published")
                .authorId(ApiKeyAccess.ownerUserIdOrFallback(request, "mcp-server"))
                .tags(tagSet)
                .byteSize((long) content.getBytes(StandardCharsets.UTF_8).length)
                .views(0)
                .build();

        KnowledgeDocument saved = knowledgeService.saveOrUpdateDocument(doc);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }
}
