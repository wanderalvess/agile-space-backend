package com.agilespace.backend.controller;

import com.agilespace.backend.domain.ApiKeyScope;
import com.agilespace.backend.domain.Prompt;
import com.agilespace.backend.domain.PromptCollection;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.ApiKeyAccess;
import com.agilespace.backend.security.ApiKeyAuthenticationFilter;
import com.agilespace.backend.service.PromptService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * API pública de leitura e escrita do Prompt Hub, autenticada por API key (ApiKeyAuthenticationFilter,
 * header X-Api-Key, mesmo padrão do KnowledgeApiV1Controller) — pensada pra chamada de
 * máquina/serviço (ex: ingestão de skills por agentes/MCP ou scripts externos).
 * Leitura sempre restrita a visibility="public", inclusive quando filtrado por authorId/ownerId.
 * Escrita protegida por escopo PROMPTHUB_WRITE, com suporte a upsert idempotente por título/name.
 */
@RestController
@RequestMapping("/api/v1/prompt-hub")
@RequiredArgsConstructor
public class PromptHubApiV1Controller {

    private final PromptService promptService;
    private final UserRepository userRepository;

    @GetMapping("/items")
    public ResponseEntity<Page<Prompt>> listItems(
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "authorId", required = false) String authorId,
            @PageableDefault(size = 20) Pageable pageable,
            HttpServletRequest request) {
        ApiKeyAccess.requireScope(request, ApiKeyScope.PROMPTHUB_READ);
        return ResponseEntity.ok(promptService.listPublicPrompts(query, authorId, pageable));
    }

    @GetMapping("/items/{id}")
    public ResponseEntity<Prompt> getItem(@PathVariable("id") UUID id, HttpServletRequest request) {
        ApiKeyAccess.requireScope(request, ApiKeyScope.PROMPTHUB_READ);
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
            @PageableDefault(size = 20) Pageable pageable,
            HttpServletRequest request) {
        ApiKeyAccess.requireScope(request, ApiKeyScope.PROMPTHUB_READ);
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
    public ResponseEntity<PromptCollection> getCollection(@PathVariable("id") UUID id, HttpServletRequest request) {
        ApiKeyAccess.requireScope(request, ApiKeyScope.PROMPTHUB_READ);
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

    @PostMapping("/items")
    public ResponseEntity<Prompt> createOrUpdateItem(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        ApiKeyAccess.requireScope(request, ApiKeyScope.PROMPTHUB_WRITE);

        String content = payload.get("content") != null ? (String) payload.get("content") : "";
        String title = (String) payload.get("title");
        String description = (String) payload.get("description");

        if ((title == null || title.isBlank()) && !content.isBlank()) {
            title = extractFrontmatterField(content, "name");
        }
        if ((description == null || description.isBlank()) && !content.isBlank()) {
            description = extractFrontmatterField(content, "description");
        }

        if (title == null || title.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        String type = payload.get("type") != null ? (String) payload.get("type") : "skill";
        String visibility = payload.get("visibility") != null ? (String) payload.get("visibility") : "public";

        String authorId = ApiKeyAccess.ownerUserIdOrFallback(request, "mcp-agent");
        String authorName = "Agente MCP";
        String authorRole = "AI Assistant";
        String authorSquad = (String) request.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SQUAD_ID);
        String authorAvatar = null;

        String ownerUserId = (String) request.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_OWNER_ID);
        if (ownerUserId != null) {
            var userOpt = userRepository.findById(ownerUserId);
            if (userOpt.isPresent()) {
                var u = userOpt.get();
                if (u.getName() != null && !u.getName().isBlank()) authorName = u.getName();
                if (u.getRole() != null && !u.getRole().isBlank()) authorRole = u.getRole();
                if (authorSquad == null && u.getSquadId() != null) authorSquad = u.getSquadId();
                authorAvatar = u.getAvatarUrl();
            }
        }

        Set<String> tagSet = new HashSet<>();
        tagSet.add("skill");
        Object tagsObj = payload.get("tags");
        if (tagsObj instanceof List<?> list) {
            for (Object t : list) {
                if (t != null && !t.toString().isBlank()) {
                    tagSet.add(t.toString().trim().replace("#", "").toLowerCase());
                }
            }
        } else if (tagsObj instanceof String str) {
            for (String t : str.split(",")) {
                String trimmed = t.trim().replace("#", "").toLowerCase();
                if (!trimmed.isEmpty()) {
                    tagSet.add(trimmed);
                }
            }
        }

        Prompt prompt = Prompt.builder()
                .title(title.trim())
                .description(description != null ? description.trim() : null)
                .content(content)
                .type(type)
                .visibility(visibility)
                .status("producao")
                .impact("medio")
                .authorId(authorId)
                .authorName(authorName)
                .authorRole(authorRole)
                .authorSquad(authorSquad)
                .authorAvatar(authorAvatar)
                .tags(tagSet)
                .build();

        Prompt saved = "skill".equalsIgnoreCase(type)
                ? promptService.saveOrUpdateSkill(prompt)
                : promptService.createPrompt(prompt);

        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("^\\s*---\\r?\\n([\\s\\S]*?)\\r?\\n---", Pattern.MULTILINE);

    private static String extractFrontmatterField(String content, String field) {
        if (content == null) return null;
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        if (!matcher.find()) return null;
        String block = matcher.group(1);
        String[] lines = block.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher fieldMatcher = Pattern.compile("^" + Pattern.quote(field) + "\\s*:\\s*(.*)$", Pattern.CASE_INSENSITIVE).matcher(line);
            if (fieldMatcher.find()) {
                String val = fieldMatcher.group(1).trim().replaceAll("^[\"']|[\"']$", "");
                if (val.equals(">-") || val.equals(">") || val.equals("|") || val.equals("|-") || val.isEmpty()) {
                    List<String> multiline = new ArrayList<>();
                    for (int j = i + 1; j < lines.length; j++) {
                        String nextLine = lines[j];
                        if (nextLine.matches("^\\s{2,}.*")) {
                            multiline.add(nextLine.trim());
                        } else if (nextLine.trim().isEmpty()) {
                            // linha em branco permitida
                        } else {
                            break;
                        }
                    }
                    if (!multiline.isEmpty()) {
                        return String.join(" ", multiline).trim();
                    }
                }
                return val.isEmpty() ? null : val;
            }
        }
        return null;
    }
}
