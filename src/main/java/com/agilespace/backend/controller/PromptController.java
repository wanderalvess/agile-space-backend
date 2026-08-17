package com.agilespace.backend.controller;

import com.agilespace.backend.domain.Prompt;
import com.agilespace.backend.domain.PromptComment;
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
@CrossOrigin(origins = "*") // Permite chamadas do frontend Next.js de qualquer porta
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
}
