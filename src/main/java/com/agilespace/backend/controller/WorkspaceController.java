package com.agilespace.backend.controller;

import com.agilespace.backend.domain.UserKanbanCard;
import com.agilespace.backend.domain.UserStickyNote;
import com.agilespace.backend.domain.UserQuickLink;
import com.agilespace.backend.service.WorkspaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {

    @Autowired
    private WorkspaceService service;

    // --- Kanban ---
    @GetMapping("/{userId}/kanban")
    public ResponseEntity<List<UserKanbanCard>> getKanbanCards(@PathVariable String userId) {
        return ResponseEntity.ok(service.getKanbanCards(userId));
    }

    @PostMapping("/{userId}/kanban")
    public ResponseEntity<UserKanbanCard> saveKanbanCard(@PathVariable String userId, @RequestBody UserKanbanCard card) {
        card.setUserId(userId);
        return ResponseEntity.ok(service.saveKanbanCard(card));
    }

    @DeleteMapping("/kanban/{id}")
    public ResponseEntity<Void> deleteKanbanCard(@PathVariable String id) {
        service.deleteKanbanCard(id);
        return ResponseEntity.noContent().build();
    }

    // --- Sticky Notes ---
    @GetMapping("/{userId}/notes")
    public ResponseEntity<List<UserStickyNote>> getStickyNotes(@PathVariable String userId) {
        return ResponseEntity.ok(service.getStickyNotes(userId));
    }

    @PostMapping("/{userId}/notes")
    public ResponseEntity<UserStickyNote> saveStickyNote(@PathVariable String userId, @RequestBody UserStickyNote note) {
        note.setUserId(userId);
        return ResponseEntity.ok(service.saveStickyNote(note));
    }

    @DeleteMapping("/notes/{id}")
    public ResponseEntity<Void> deleteStickyNote(@PathVariable String id) {
        service.deleteStickyNote(id);
        return ResponseEntity.noContent().build();
    }

    // --- Quick Links ---
    @GetMapping("/{userId}/links")
    public ResponseEntity<List<UserQuickLink>> getQuickLinks(@PathVariable String userId) {
        return ResponseEntity.ok(service.getQuickLinks(userId));
    }

    @PostMapping("/{userId}/links")
    public ResponseEntity<UserQuickLink> saveQuickLink(@PathVariable String userId, @RequestBody UserQuickLink link) {
        link.setUserId(userId);
        return ResponseEntity.ok(service.saveQuickLink(link));
    }

    @DeleteMapping("/links/{id}")
    public ResponseEntity<Void> deleteQuickLink(@PathVariable String id) {
        service.deleteQuickLink(id);
        return ResponseEntity.noContent().build();
    }
}
