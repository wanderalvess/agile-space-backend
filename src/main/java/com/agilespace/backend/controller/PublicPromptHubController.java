package com.agilespace.backend.controller;

import com.agilespace.backend.domain.Prompt;
import com.agilespace.backend.service.PromptService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping("/items")
    @Transactional(readOnly = true)
    public ResponseEntity<Page<PublicPromptView>> list(
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        String q = query == null ? null : query.trim();
        if (q != null && q.length() > MAX_QUERY_LENGTH) q = q.substring(0, MAX_QUERY_LENGTH);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return ResponseEntity.ok(promptService.listPublicPrompts(q, null, pageable).map(PublicPromptView::of));
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
