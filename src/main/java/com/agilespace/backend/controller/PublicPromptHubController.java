package com.agilespace.backend.controller;

import com.agilespace.backend.domain.Prompt;
import com.agilespace.backend.domain.PromptCollection;
import com.agilespace.backend.service.PromptService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.agilespace.backend.dto.PromptFacetsDto;
import com.agilespace.backend.dto.PromptPageDto;
import com.agilespace.backend.repository.PromptFilter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Leitura anônima da Biblioteca de IA (rota liberada pelo prefixo /api/public no JwtAuthenticationFilter).
 * Só GET e só itens com visibilidade "public". Item privado e inexistente respondem 404 igual.
 * A resposta é um DTO próprio: sem authorId (identificador interno), sem escrita, sem comentários, sem coleções.
 */
@RestController
@RequestMapping("/api/public/prompt-hub")
@RequiredArgsConstructor
public class PublicPromptHubController {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_QUERY_LENGTH = 100;
    private static final int MAX_COLLECTION_PAGE_SIZE = 200;

    private final PromptService promptService;

    public record PublicPromptView(
            UUID id, String title, String description, String content, String type,
            String status, String impact, String businessGoal, String targetAudience,
            String gemLink, String architectureLink,
            String authorName, String authorRole, String authorSquad, String authorAvatar,
            Integer useCount, Integer forkCount, List<String> tags,
            LocalDateTime createdAt, LocalDateTime updatedAt) {

        static PublicPromptView of(Prompt p) {
            return new PublicPromptView(
                    p.getId(), p.getTitle(), p.getDescription(), p.getContent(), p.getType(),
                    p.getStatus(), p.getImpact(), p.getBusinessGoal(), p.getTargetAudience(),
                    p.getGemLink(), p.getArchitectureLink(),
                    p.getAuthorName(), p.getAuthorRole(), p.getAuthorSquad(), p.getAuthorAvatar(),
                    p.getUseCount(), p.getForkCount(),
                    p.getTags() == null ? List.of() : List.copyOf(p.getTags()),
                    p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    /** Coleção pública para visitantes: sem ownerId (interno), itens públicos e contagens; nunca editável. */
    public record PublicCollectionView(
            UUID id, String name, String description, String visibility, String ownerId, String ownerName,
            List<PublicPromptView> items, LocalDateTime createdAt,
            int itemCount, int hiddenCount, boolean canEdit) {

        static PublicCollectionView of(PromptCollection c) {
            List<PublicPromptView> items = c.getItems() == null ? List.of()
                    : c.getItems().stream().map(PublicPromptView::of).toList();
            int total = c.getItemCount() != null ? c.getItemCount() : items.size();
            int hidden = c.getHiddenCount() != null ? c.getHiddenCount() : 0;
            return new PublicCollectionView(c.getId(), c.getName(), c.getDescription(), "public", "",
                    c.getOwnerName(), items, c.getCreatedAt(), total, hidden, false);
        }
    }

    @GetMapping("/collections")
    @Transactional(readOnly = true)
    public ResponseEntity<PromptPageDto<PublicCollectionView>> listCollections(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", required = false) Integer size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size == null ? 20 : size, 1), MAX_COLLECTION_PAGE_SIZE));
        Page<PromptCollection> result = promptService.listCollections("public", null, null, pageable);
        return ResponseEntity.ok(new PromptPageDto<>(
                result.getContent().stream().map(PublicCollectionView::of).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }

    @GetMapping("/collections/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<PublicCollectionView> getCollection(@PathVariable("id") UUID id) {
        try {
            return ResponseEntity.ok(PublicCollectionView.of(promptService.getPublicCollection(id)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Espelha o contrato de GET /api/prompts para visitantes (sempre só públicos, sem favorite/canEdit).
     * Sem nenhum parâmetro novo (type, status, impact, tag, sort, query) mantém o formato antigo (Page do Spring).
     */
    @GetMapping("/items")
    @Transactional(readOnly = true)
    public ResponseEntity<Object> list(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "query", required = false) String queryParam,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "impact", required = false) String impact,
            @RequestParam(value = "tag", required = false) String tag,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", required = false) Integer size) {
        String text = cap(queryParam != null ? queryParam : q);
        boolean v2 = queryParam != null || type != null || status != null || impact != null || tag != null || sort != null;
        if (!v2) {
            PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size == null ? 20 : size, 1), MAX_PAGE_SIZE));
            return ResponseEntity.ok(promptService.listPublicPrompts(text, null, pageable).map(PublicPromptView::of));
        }
        PromptFilter filter = new PromptFilter(text, type, "public", status, impact, tag, false, null);
        Page<Prompt> result = promptService.searchPrompts(filter, sort, null, page, Math.min(size == null ? 24 : size, MAX_PAGE_SIZE));
        return ResponseEntity.ok(new PromptPageDto<>(
                result.getContent().stream().map(PublicPromptView::of).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }

    @GetMapping("/facets")
    @Transactional(readOnly = true)
    public ResponseEntity<PromptFacetsDto> facets(
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "impact", required = false) String impact,
            @RequestParam(value = "tag", required = false) String tag) {
        PromptFilter filter = new PromptFilter(cap(query), type, "public", status, impact, tag, false, null);
        return ResponseEntity.ok(promptService.facets(filter, null));
    }

    private static String cap(String query) {
        String q = query == null ? null : query.trim();
        if (q != null && q.length() > MAX_QUERY_LENGTH) q = q.substring(0, MAX_QUERY_LENGTH);
        return q;
    }

    @GetMapping("/items/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<PublicPromptView> get(@PathVariable("id") UUID id) {
        try {
            Prompt prompt = promptService.getPromptById(id);
            if (!"public".equals(prompt.getVisibility())) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(PublicPromptView.of(prompt));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
