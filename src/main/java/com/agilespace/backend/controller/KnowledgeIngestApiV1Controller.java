package com.agilespace.backend.controller;

import com.agilespace.backend.domain.ApiKeyScope;
import com.agilespace.backend.dto.KnowledgeIngestDtos.ManifestEntry;
import com.agilespace.backend.dto.KnowledgeIngestDtos.Request;
import com.agilespace.backend.dto.KnowledgeIngestDtos.Response;
import com.agilespace.backend.security.ApiKeyAccess;
import com.agilespace.backend.service.KnowledgeIngestService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Ingestão em lote da Base de Conhecimento por API key (o Hub envia docs de API, páginas do TDN, prompts,
 * skills e instructions). Ler o manifesto exige KNOWLEDGE_READ; gravar e apagar exigem KNOWLEDGE_WRITE.
 */
@RestController
@RequestMapping("/api/v1/knowledge/ingest")
@RequiredArgsConstructor
public class KnowledgeIngestApiV1Controller {

    private final KnowledgeIngestService ingestService;

    /** Lista externalId + hash do que já existe, para o cliente mandar só o que mudou. */
    @GetMapping("/manifest")
    public ResponseEntity<List<ManifestEntry>> manifest(@RequestParam(value = "docType", required = false) String docType,
                                                        HttpServletRequest request) {
        ApiKeyAccess.requireScope(request, ApiKeyScope.KNOWLEDGE_READ);
        return ResponseEntity.ok(ingestService.manifest(docType));
    }

    @PostMapping
    public ResponseEntity<Response> ingest(@RequestBody Request body, HttpServletRequest request) {
        ApiKeyAccess.requireScope(request, ApiKeyScope.KNOWLEDGE_WRITE);
        String actor = ApiKeyAccess.ownerUserIdOrFallback(request, "hub-ingest");
        return ResponseEntity.ok(ingestService.ingest(body == null ? null : body.items(), actor));
    }

    /** Manda para a lixeira (restaurável pela UI). O externalId vai em query porque costuma ter barras. */
    @DeleteMapping
    public ResponseEntity<Void> delete(@RequestParam("docType") String docType,
                                       @RequestParam("externalId") String externalId,
                                       HttpServletRequest request) {
        ApiKeyAccess.requireScope(request, ApiKeyScope.KNOWLEDGE_WRITE);
        String actor = ApiKeyAccess.ownerUserIdOrFallback(request, "hub-ingest");
        return ingestService.delete(docType, externalId, actor)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
