package com.agilespace.backend.controller;

import com.agilespace.backend.domain.Prompt;
import com.agilespace.backend.domain.PromptComment;
import com.agilespace.backend.domain.PromptCollection;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.PromptCaller;
import com.agilespace.backend.service.PromptService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Biblioteca de IA. A identidade vem sempre do JWT (atributos do request): autoria, dono de
 * coleção e autor de comentário nunca são lidos do corpo. Item não público só aparece para o autor
 * (ou ADMIN); para os demais ele responde 404.
 */
@RestController
@RequestMapping("/api/prompts")
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class PromptController {

    private static final int MAX_BATCH = 200;

    private final PromptService promptService;
    private final Validator validator;

    private static PromptCaller caller(HttpServletRequest request) {
        String id = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        if (id == null || id.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Identidade ausente");
        }
        return new PromptCaller(id, (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE));
    }

    @GetMapping
    public ResponseEntity<Page<Prompt>> listPrompts(
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "authorId", required = false) String authorId,
            @PageableDefault(size = 12) Pageable pageable,
            HttpServletRequest request) {
        return ResponseEntity.ok(promptService.listPrompts(query, authorId, caller(request), pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Prompt> getPromptById(@PathVariable("id") UUID id, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(promptService.getVisiblePrompt(id, caller(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping
    public ResponseEntity<Prompt> createPrompt(@Valid @RequestBody Prompt prompt, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(promptService.createPrompt(prompt, caller(request)));
    }

    @PostMapping("/batch")
    public ResponseEntity<List<Prompt>> createPromptsBatch(@RequestBody List<Prompt> prompts, HttpServletRequest request) {
        if (prompts == null || prompts.isEmpty() || prompts.size() > MAX_BATCH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Envie de 1 a " + MAX_BATCH + " itens");
        }
        for (Prompt p : prompts) {
            Set<ConstraintViolation<Prompt>> violations = validator.validate(p);
            if (!violations.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, violations.iterator().next().getMessage());
            }
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(promptService.createPromptsBatch(prompts, caller(request)));
    }

    /** Upsert de skill por autor+título — usado pelo fallback item-a-item do import em lote (SkillImportDialog). */
    @PostMapping("/skill-upsert")
    public ResponseEntity<Prompt> upsertSkill(@Valid @RequestBody Prompt prompt, HttpServletRequest request) {
        return ResponseEntity.ok(promptService.saveOrUpdateSkill(prompt, caller(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Prompt> updatePrompt(@PathVariable("id") UUID id, @Valid @RequestBody Prompt prompt,
                                               HttpServletRequest request) {
        try {
            return ResponseEntity.ok(promptService.updatePrompt(id, prompt, caller(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePrompt(@PathVariable("id") UUID id, HttpServletRequest request) {
        try {
            promptService.deletePrompt(id, caller(request));
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/use")
    public ResponseEntity<Prompt> incrementUse(@PathVariable("id") UUID id, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(promptService.incrementUseCount(id, caller(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/fork")
    public ResponseEntity<Prompt> incrementFork(@PathVariable("id") UUID id, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(promptService.incrementForkCount(id, caller(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** Duplica o item para a biblioteca privada de quem chama e conta o clone na origem (atômico). */
    @PostMapping("/{id}/clone")
    public ResponseEntity<Prompt> clonePrompt(@PathVariable("id") UUID id,
                                              @RequestBody(required = false) Prompt snapshot,
                                              HttpServletRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(promptService.clonePrompt(id, snapshot, caller(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // Comentários
    @GetMapping("/{id}/comments")
    public ResponseEntity<List<PromptComment>> getComments(@PathVariable("id") UUID id, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(promptService.getComments(id, caller(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/comments")
    public ResponseEntity<PromptComment> addComment(
            @PathVariable("id") UUID id,
            @Valid @RequestBody PromptComment comment,
            HttpServletRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(promptService.addComment(id, comment, caller(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(
            @PathVariable("id") UUID id,
            @PathVariable("commentId") UUID commentId,
            HttpServletRequest request) {
        try {
            promptService.deleteComment(id, commentId, caller(request));
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // Coleções
    @GetMapping("/collections")
    public ResponseEntity<Page<PromptCollection>> listCollections(
            @RequestParam(value = "visibility", required = false) String visibility,
            @RequestParam(value = "ownerId", required = false) String ownerId,
            @PageableDefault(size = 12) Pageable pageable,
            HttpServletRequest request) {
        return ResponseEntity.ok(promptService.listCollections(visibility, ownerId, caller(request), pageable));
    }

    @GetMapping("/collections/{id}")
    public ResponseEntity<PromptCollection> getCollectionById(@PathVariable("id") UUID id, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(promptService.getCollectionFor(id, caller(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/collections")
    public ResponseEntity<PromptCollection> createCollection(@Valid @RequestBody PromptCollection collection,
                                                             HttpServletRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(promptService.createCollection(collection, caller(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }

    @PutMapping("/collections/{id}")
    public ResponseEntity<PromptCollection> updateCollection(
            @PathVariable("id") UUID id,
            @Valid @RequestBody PromptCollection collection,
            HttpServletRequest request) {
        try {
            return ResponseEntity.ok(promptService.updateCollection(id, collection, caller(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/collections/{id}")
    public ResponseEntity<Void> deleteCollection(@PathVariable("id") UUID id, HttpServletRequest request) {
        try {
            promptService.deleteCollection(id, caller(request));
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/collections/{id}/items/{promptId}")
    public ResponseEntity<PromptCollection> addItemToCollection(
            @PathVariable("id") UUID id,
            @PathVariable("promptId") UUID promptId,
            HttpServletRequest request) {
        try {
            return ResponseEntity.ok(promptService.addItemToCollection(id, promptId, caller(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/collections/{id}/items/{promptId}")
    public ResponseEntity<PromptCollection> removeItemFromCollection(
            @PathVariable("id") UUID id,
            @PathVariable("promptId") UUID promptId,
            HttpServletRequest request) {
        try {
            return ResponseEntity.ok(promptService.removeItemFromCollection(id, promptId, caller(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
