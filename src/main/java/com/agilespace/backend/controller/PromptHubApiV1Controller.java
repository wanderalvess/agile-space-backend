package com.agilespace.backend.controller;

import com.agilespace.backend.domain.Prompt;
import com.agilespace.backend.domain.PromptCollection;
import com.agilespace.backend.service.PromptService;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.stream.Collectors;

/**
 * API pública de leitura do Prompt Hub, autenticada por API key (ApiKeyAuthenticationFilter,
 * header X-Api-Key, mesmo padrão do KnowledgeApiV1Controller) — pensada pra chamada de
 * máquina/serviço (ex: ingestão pela Lynn/TOTVS), espelhando o contrato já publicado pelo
 * legado (Agile-Space) em /api/v1/prompt-hub/**. Sempre restrito a visibility="public",
 * inclusive quando filtrado por authorId/ownerId (ver PromptService.listPublicPrompts) —
 * uma API key nunca deve enxergar mais do que um visitante anônimo do Prompt Hub veria.
 */
@RestController
@RequestMapping("/api/v1/prompt-hub")
@RequiredArgsConstructor
public class PromptHubApiV1Controller {

    private final PromptService promptService;

    @GetMapping("/items")
    public ResponseEntity<Page<Prompt>> listItems(
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "authorId", required = false) String authorId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(promptService.listPublicPrompts(query, authorId, pageable));
    }

    @GetMapping("/items/{id}")
    public ResponseEntity<Prompt> getItem(@PathVariable("id") UUID id) {
        try {
            Prompt prompt = promptService.getPromptById(id);
            // Não distingue inexistente de privado (404 nos dois casos) — evita confirmar
            // pra quem não tem acesso que um id privado existe.
            if (!"public".equals(prompt.getVisibility())) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(prompt);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/collections")
    @Transactional(readOnly = true)
    public ResponseEntity<Page<PromptCollection>> listCollections(
            @RequestParam(value = "ownerId", required = false) String ownerId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<PromptCollection> result = promptService.listCollections("public", ownerId, pageable);
        // Mesmo filtro de item privado do getCollection abaixo — a coleção em si já é
        // pública, mas cada uma carrega seus próprios items via @ManyToMany, e sem esse
        // filtro aqui um item privado dentro de uma coleção pública vazava na listagem
        // (só o GET por id estava filtrando; a lista serializa direto sem passar por lá).
        result.getContent().forEach(collection -> collection.setItems(
                collection.getItems().stream()
                        .filter(p -> "public".equals(p.getVisibility()))
                        .collect(Collectors.toList())));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/collections/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<PromptCollection> getCollection(@PathVariable("id") UUID id) {
        try {
            PromptCollection collection = promptService.getCollectionById(id);
            if (!"public".equals(collection.getVisibility())) {
                return ResponseEntity.notFound().build();
            }
            // items é @ManyToMany lazy — força o fetch dentro da transação (mesmo motivo do
            // McpPromptHubTools) e filtra item privado dentro de coleção pública, sem
            // derrubar a resposta inteira.
            Hibernate.initialize(collection.getItems());
            collection.setItems(collection.getItems().stream()
                    .filter(p -> "public".equals(p.getVisibility()))
                    .collect(Collectors.toList()));
            return ResponseEntity.ok(collection);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
