package com.agilespace.backend.controller;

import com.agilespace.backend.domain.Prompt;
import com.agilespace.backend.domain.PromptComment;
import com.agilespace.backend.domain.PromptCollection;
import com.agilespace.backend.service.PromptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/prompts")
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class PromptController {

    private final PromptService promptService;

    @GetMapping
    public ResponseEntity<Page<Prompt>> listPrompts(
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "authorId", required = false) String authorId,
            @PageableDefault(size = 12) Pageable pageable) {
        return ResponseEntity.ok(promptService.listPrompts(query, authorId, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Prompt> getPromptById(@PathVariable("id") UUID id) {
        try {
            return ResponseEntity.ok(promptService.getPromptById(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping
    public ResponseEntity<Prompt> createPrompt(@Valid @RequestBody Prompt prompt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(promptService.createPrompt(prompt));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Prompt> updatePrompt(@PathVariable("id") UUID id, @Valid @RequestBody Prompt prompt) {
        try {
            return ResponseEntity.ok(promptService.updatePrompt(id, prompt));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePrompt(@PathVariable("id") UUID id) {
        try {
            promptService.deletePrompt(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/use")
    public ResponseEntity<Prompt> incrementUse(@PathVariable("id") UUID id) {
        try {
            return ResponseEntity.ok(promptService.incrementUseCount(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/fork")
    public ResponseEntity<Prompt> incrementFork(@PathVariable("id") UUID id) {
        try {
            return ResponseEntity.ok(promptService.incrementForkCount(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // Comentários
    @GetMapping("/{id}/comments")
    public ResponseEntity<List<PromptComment>> getComments(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(promptService.getComments(id));
    }

    @PostMapping("/{id}/comments")
    public ResponseEntity<PromptComment> addComment(
            @PathVariable("id") UUID id,
            @Valid @RequestBody PromptComment comment) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(promptService.addComment(id, comment));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // Coleções
    @GetMapping("/collections")
    public ResponseEntity<Page<PromptCollection>> listCollections(
            @RequestParam(value = "visibility", required = false) String visibility,
            @RequestParam(value = "ownerId", required = false) String ownerId,
            @PageableDefault(size = 12) Pageable pageable) {
        return ResponseEntity.ok(promptService.listCollections(visibility, ownerId, pageable));
    }

    @GetMapping("/collections/{id}")
    public ResponseEntity<PromptCollection> getCollectionById(@PathVariable("id") UUID id) {
        try {
            return ResponseEntity.ok(promptService.getCollectionById(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/collections")
    public ResponseEntity<PromptCollection> createCollection(@Valid @RequestBody PromptCollection collection) {
        return ResponseEntity.status(HttpStatus.CREATED).body(promptService.createCollection(collection));
    }

    @PutMapping("/collections/{id}")
    public ResponseEntity<PromptCollection> updateCollection(
            @PathVariable("id") UUID id,
            @Valid @RequestBody PromptCollection collection) {
        try {
            return ResponseEntity.ok(promptService.updateCollection(id, collection));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/collections/{id}")
    public ResponseEntity<Void> deleteCollection(@PathVariable("id") UUID id) {
        try {
            promptService.deleteCollection(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/collections/{id}/items/{promptId}")
    public ResponseEntity<PromptCollection> addItemToCollection(
            @PathVariable("id") UUID id,
            @PathVariable("promptId") UUID promptId) {
        try {
            return ResponseEntity.ok(promptService.addItemToCollection(id, promptId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/collections/{id}/items/{promptId}")
    public ResponseEntity<PromptCollection> removeItemFromCollection(
            @PathVariable("id") UUID id,
            @PathVariable("promptId") UUID promptId) {
        try {
            return ResponseEntity.ok(promptService.removeItemFromCollection(id, promptId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
