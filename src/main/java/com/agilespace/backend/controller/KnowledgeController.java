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
import com.agilespace.backend.dto.KnowledgeVersionDto;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final KnowledgeTokenUsageRepository knowledgeTokenUsageRepository;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<Page<KnowledgeDocument>> listDocuments(
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "tags", required = false) Set<String> tags,
            @RequestParam(value = "status", required = false) String status,
            @PageableDefault(size = 20) Pageable pageable,
            HttpServletRequest request) {
        Page<KnowledgeDocument> page = knowledgeService.listDocuments(query, tags, status, pageable, resolveUserId(request), isAdmin(request));
        knowledgeService.markEditable(page.getContent(), resolveUserId(request), isAdmin(request));
        return ResponseEntity.ok(page);
    }

    // Overload preservado para compatibilidade de origem com chamadas existentes sem filtro de status.
    public ResponseEntity<Page<KnowledgeDocument>> listDocuments(String query, Set<String> tags, Pageable pageable) {
        return listDocuments(query, tags, null, pageable, null);
    }

    @PostMapping("/search/semantic")
    public ResponseEntity<Page<KnowledgeDocument>> semanticSearch(
            @RequestBody Map<String, Object> body,
            @PageableDefault(size = 10) Pageable pageable) {
        Object raw = body.get("embedding");
        if (!(raw instanceof List<?> rawEmbedding) || rawEmbedding.isEmpty() || rawEmbedding.size() > KnowledgeService.MAX_EMBEDDING_DIMS) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Informe 'embedding': uma lista de números com 1 a " + KnowledgeService.MAX_EMBEDDING_DIMS + " posições.");
        }
        float[] queryEmbedding = new float[rawEmbedding.size()];
        for (int i = 0; i < rawEmbedding.size(); i++) {
            // JSON com inteiros (ex.: 0) chegava como Integer e estourava ClassCastException (500).
            if (!(rawEmbedding.get(i) instanceof Number n)) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        "O 'embedding' deve conter apenas números.");
            }
            queryEmbedding[i] = n.floatValue();
        }
        return ResponseEntity.ok(knowledgeService.semanticSearch(queryEmbedding, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<KnowledgeDocument> getDocumentById(@PathVariable("id") UUID id, HttpServletRequest request) {
        try {
            KnowledgeDocument doc = knowledgeService.getReadableDocument(id, resolveUserId(request), isAdmin(request));
            knowledgeService.markEditable(List.of(doc), resolveUserId(request), isAdmin(request));
            return ResponseEntity.ok(doc);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // Criar/editar é aberto a qualquer autenticado (base de conhecimento colaborativa da
    // empresa inteira) — só authorId/updatedBy passam a vir sempre do token, nunca do corpo,
    // pra não dar pra um autor forjar a autoria de outra pessoa.
    @PostMapping
    public ResponseEntity<KnowledgeDocument> saveOrUpdateDocument(@Valid @RequestBody KnowledgeDocument doc, HttpServletRequest request) {
        String callerId = resolveUserId(request);
        doc.setAuthorId(callerId);
        KnowledgeDocument saved = knowledgeService.saveOrUpdateDocument(doc, isAdmin(request));
        knowledgeService.markEditable(List.of(saved), callerId, isAdmin(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<KnowledgeDocument> updateDocument(
            @PathVariable("id") UUID id,
            @Valid @RequestBody KnowledgeDocument doc,
            HttpServletRequest request) {
        String callerId = resolveUserId(request);
        doc.setUpdatedBy(callerId);
        try {
            KnowledgeDocument saved = knowledgeService.updateDocument(id, doc, callerId, isAdmin(request));
            knowledgeService.markEditable(List.of(saved), callerId, isAdmin(request));
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // Apagar é restrito: só o autor original ou um ADMIN — checado no service, que já
    // busca o documento pra saber o authorId.
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(@PathVariable("id") UUID id, HttpServletRequest request) {
        String callerId = resolveUserId(request);
        try {
            knowledgeService.deleteDocument(id, callerId, isAdmin(request));
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id}/versions")
    public ResponseEntity<List<KnowledgeVersionDto>> listVersions(@PathVariable("id") UUID id, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(knowledgeService.listVersions(id, resolveUserId(request), isAdmin(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id}/versions/{number}")
    public ResponseEntity<KnowledgeVersionDto> getVersion(@PathVariable("id") UUID id, @PathVariable("number") int number, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(knowledgeService.getVersion(id, number, resolveUserId(request), isAdmin(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** Restaura uma versão (cria uma nova versão). Mesma permissão de edição. */
    @PostMapping("/{id}/versions/{number}/restore")
    public ResponseEntity<KnowledgeDocument> restoreVersion(@PathVariable("id") UUID id, @PathVariable("number") int number, HttpServletRequest request) {
        String callerId = resolveUserId(request);
        try {
            KnowledgeDocument saved = knowledgeService.restoreVersion(id, number, callerId, isAdmin(request));
            knowledgeService.markEditable(List.of(saved), callerId, isAdmin(request));
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/view")
    public ResponseEntity<KnowledgeDocument> incrementViews(@PathVariable("id") UUID id, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(knowledgeService.incrementViews(id, resolveUserId(request), isAdmin(request)));
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
        long tokens = body.get("tokens") instanceof Number n ? n.longValue() : 0L;
        knowledgeService.incrementTokenUsage(userId, userName, tokens);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/token-usage/top")
    public ResponseEntity<List<KnowledgeTokenUsage>> getTopTokenUsage() {
        return ResponseEntity.ok(knowledgeService.getTopTokenUsage());
    }

    private boolean isAdmin(HttpServletRequest request) {
        return request != null && "ADMIN".equalsIgnoreCase((String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE));
    }

    private String resolveUserId(HttpServletRequest request) {
        if (request == null) return null;
        return (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
    }
}
