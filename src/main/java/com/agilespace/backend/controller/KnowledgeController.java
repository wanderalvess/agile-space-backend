package com.agilespace.backend.controller;

import com.agilespace.backend.domain.KnowledgeDocument;
import com.agilespace.backend.service.KnowledgeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    @GetMapping
    public ResponseEntity<Page<KnowledgeDocument>> listDocuments(
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "tags", required = false) Set<String> tags,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(knowledgeService.listDocuments(query, tags, pageable));
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
}
