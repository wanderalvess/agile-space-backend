package com.agilespace.backend.controller;

import com.agilespace.backend.domain.UserKanbanCard;
import com.agilespace.backend.domain.UserStickyNote;
import com.agilespace.backend.domain.UserQuickLink;
import com.agilespace.backend.domain.UserSnippet;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.WorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * Espaço pessoal (kanban, notas, links, snippets): cada usuário só enxerga e altera o próprio.
 * O {userId} da rota tem que ser o do token, e os deletes/updates por id conferem o dono no
 * service — sem isso qualquer logado lia e apagava o espaço de qualquer colega.
 */
@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {

    @Autowired
    private WorkspaceService service;

    // --- Kanban ---
    @GetMapping("/{userId}/kanban")
    public ResponseEntity<List<UserKanbanCard>> getKanbanCards(@PathVariable String userId, HttpServletRequest request) {
        requireSelf(userId, request);
        return ResponseEntity.ok(service.getKanbanCards(userId));
    }

    @PostMapping("/{userId}/kanban")
    public ResponseEntity<UserKanbanCard> saveKanbanCard(@PathVariable String userId, @RequestBody UserKanbanCard card, HttpServletRequest request) {
        requireSelf(userId, request);
        card.setUserId(userId);
        return ResponseEntity.ok(service.saveKanbanCard(card));
    }

    @DeleteMapping("/kanban/{id}")
    public ResponseEntity<Void> deleteKanbanCard(@PathVariable String id, HttpServletRequest request) {
        service.deleteKanbanCard(id, callerId(request));
        return ResponseEntity.noContent().build();
    }

    // --- Sticky Notes ---
    @GetMapping("/{userId}/notes")
    public ResponseEntity<List<UserStickyNote>> getStickyNotes(@PathVariable String userId, HttpServletRequest request) {
        requireSelf(userId, request);
        return ResponseEntity.ok(service.getStickyNotes(userId));
    }

    @PostMapping("/{userId}/notes")
    public ResponseEntity<UserStickyNote> saveStickyNote(@PathVariable String userId, @RequestBody UserStickyNote note, HttpServletRequest request) {
        requireSelf(userId, request);
        note.setUserId(userId);
        return ResponseEntity.ok(service.saveStickyNote(note));
    }

    @DeleteMapping("/notes/{id}")
    public ResponseEntity<Void> deleteStickyNote(@PathVariable String id, HttpServletRequest request) {
        service.deleteStickyNote(id, callerId(request));
        return ResponseEntity.noContent().build();
    }

    // --- Quick Links ---
    @GetMapping("/{userId}/links")
    public ResponseEntity<List<UserQuickLink>> getQuickLinks(@PathVariable String userId, HttpServletRequest request) {
        requireSelf(userId, request);
        return ResponseEntity.ok(service.getQuickLinks(userId));
    }

    @PostMapping("/{userId}/links")
    public ResponseEntity<UserQuickLink> saveQuickLink(@PathVariable String userId, @RequestBody UserQuickLink link, HttpServletRequest request) {
        requireSelf(userId, request);
        link.setUserId(userId);
        return ResponseEntity.ok(service.saveQuickLink(link));
    }

    @DeleteMapping("/links/{id}")
    public ResponseEntity<Void> deleteQuickLink(@PathVariable String id, HttpServletRequest request) {
        service.deleteQuickLink(id, callerId(request));
        return ResponseEntity.noContent().build();
    }

    // --- Snippets ---
    @GetMapping("/{userId}/snippets")
    public ResponseEntity<List<UserSnippet>> getSnippets(@PathVariable String userId, HttpServletRequest request) {
        requireSelf(userId, request);
        return ResponseEntity.ok(service.getSnippets(userId));
    }

    @PostMapping("/{userId}/snippets")
    public ResponseEntity<UserSnippet> saveSnippet(@PathVariable String userId, @RequestBody UserSnippet snippet, HttpServletRequest request) {
        requireSelf(userId, request);
        snippet.setUserId(userId);
        return ResponseEntity.ok(service.saveSnippet(snippet));
    }

    @DeleteMapping("/snippets/{id}")
    public ResponseEntity<Void> deleteSnippet(@PathVariable String id, HttpServletRequest request) {
        service.deleteSnippet(id, callerId(request));
        return ResponseEntity.noContent().build();
    }

    private static String callerId(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
    }

    private static void requireSelf(String userId, HttpServletRequest request) {
        String caller = callerId(request);
        if (caller == null || !caller.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito ao próprio espaço de trabalho");
        }
    }
}
