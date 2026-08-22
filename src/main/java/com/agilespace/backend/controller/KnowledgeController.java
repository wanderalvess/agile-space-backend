package com.agilespace.backend.controller;

import com.agilespace.backend.domain.KnowledgeConversation;
import com.agilespace.backend.domain.KnowledgeDocument;
import com.agilespace.backend.domain.KnowledgeTokenUsage;
import com.agilespace.backend.domain.KnowledgeUserAiSettings;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.KnowledgeTokenUsageRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.KnowledgeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final KnowledgeTokenUsageRepository knowledgeTokenUsageRepository;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<Page<KnowledgeDocument>> listDocuments(
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "tags", required = false) Set<String> tags,
            @RequestParam(value = "status", required = false) String status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(knowledgeService.listDocuments(query, tags, status, pageable));
    }

    // Overload preservado para compatibilidade de origem com chamadas existentes sem filtro de status.
    public ResponseEntity<Page<KnowledgeDocument>> listDocuments(String query, Set<String> tags, Pageable pageable) {
        return listDocuments(query, tags, null, pageable);
    }

    @GetMapping("/{id}")
    public ResponseEntity<KnowledgeDocument> getDocumentById(@PathVariable("id") UUID id) {
        try {
            return ResponseEntity.ok(knowledgeService.getDocumentById(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping
    public ResponseEntity<KnowledgeDocument> saveOrUpdateDocument(@Valid @RequestBody KnowledgeDocument doc) {
        return ResponseEntity.status(HttpStatus.CREATED).body(knowledgeService.saveOrUpdateDocument(doc));
    }

    @PutMapping("/{id}")
    public ResponseEntity<KnowledgeDocument> updateDocument(
            @PathVariable("id") UUID id,
            @Valid @RequestBody KnowledgeDocument doc) {
        try {
            return ResponseEntity.ok(knowledgeService.updateDocument(id, doc));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable("id") UUID id,
            @RequestParam("deletedBy") String deletedBy) {
        try {
            knowledgeService.deleteDocument(id, deletedBy);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/view")
    public ResponseEntity<KnowledgeDocument> incrementViews(@PathVariable("id") UUID id) {
        try {
            return ResponseEntity.ok(knowledgeService.incrementViews(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/conversations")
    public ResponseEntity<List<KnowledgeConversation>> listConversations(HttpServletRequest request) {
        return ResponseEntity.ok(knowledgeService.listConversations(resolveUserId(request)));
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<KnowledgeConversation> getConversation(
            @PathVariable("id") UUID id, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(knowledgeService.getConversation(id, resolveUserId(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/conversations")
    public ResponseEntity<KnowledgeConversation> createConversation(
            @RequestBody Map<String, String> body, HttpServletRequest request) {
        KnowledgeConversation created = knowledgeService.createConversation(resolveUserId(request), body.get("title"));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/conversations/{id}")
    public ResponseEntity<KnowledgeConversation> renameConversation(
            @PathVariable("id") UUID id, @RequestBody Map<String, String> body, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(
                    knowledgeService.renameConversation(id, resolveUserId(request), body.get("title")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/conversations/{id}/messages")
    public ResponseEntity<KnowledgeConversation> appendMessage(
            @PathVariable("id") UUID id, @RequestBody Map<String, Object> message, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(knowledgeService.appendMessage(id, resolveUserId(request), message));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Void> deleteConversation(@PathVariable("id") UUID id, HttpServletRequest request) {
        try {
            knowledgeService.deleteConversation(id, resolveUserId(request));
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/ai-settings")
    public ResponseEntity<KnowledgeUserAiSettings> getAiSettings(HttpServletRequest request) {
        return ResponseEntity.ok(knowledgeService.getAiSettings(resolveUserId(request)));
    }

    @PutMapping("/ai-settings")
    public ResponseEntity<KnowledgeUserAiSettings> saveAiSettings(
            @RequestBody KnowledgeUserAiSettings updates, HttpServletRequest request) {
        return ResponseEntity.ok(knowledgeService.saveAiSettings(resolveUserId(request), updates));
    }

    @GetMapping("/token-usage")
    public ResponseEntity<KnowledgeTokenUsage> getTokenUsage(HttpServletRequest request) {
        String userId = resolveUserId(request);
        KnowledgeTokenUsage usage = knowledgeTokenUsageRepository.findById(userId)
                .orElseGet(() -> KnowledgeTokenUsage.builder().userId(userId).totalTokens(0L).build());
        return ResponseEntity.ok(usage);
    }

    @PostMapping("/token-usage/increment")
    public ResponseEntity<Void> incrementTokenUsage(
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        String userId = resolveUserId(request);
        String userName = userRepository.findById(userId).map(User::getName).orElse(userId);
        long tokens = body.get("tokens") != null ? ((Number) body.get("tokens")).longValue() : 0L;
        knowledgeService.incrementTokenUsage(userId, userName, tokens);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/token-usage/top")
    public ResponseEntity<List<KnowledgeTokenUsage>> getTopTokenUsage() {
        return ResponseEntity.ok(knowledgeService.getTopTokenUsage());
    }

    private String resolveUserId(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
    }
}
