package com.agilespace.backend.service;

import com.agilespace.backend.domain.UserKanbanCard;
import com.agilespace.backend.domain.UserStickyNote;
import com.agilespace.backend.domain.UserQuickLink;
import com.agilespace.backend.domain.UserSnippet;
import com.agilespace.backend.repository.UserKanbanCardRepository;
import com.agilespace.backend.repository.UserStickyNoteRepository;
import com.agilespace.backend.repository.UserQuickLinkRepository;
import com.agilespace.backend.repository.UserSnippetRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class WorkspaceService {

    @Autowired
    private UserKanbanCardRepository kanbanRepository;

    @Autowired
    private UserStickyNoteRepository noteRepository;

    @Autowired
    private UserQuickLinkRepository linkRepository;

    @Autowired
    private UserSnippetRepository snippetRepository;

    // --- Kanban ---
    @Transactional(readOnly = true)
    public List<UserKanbanCard> getKanbanCards(String userId) {
        return kanbanRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    @Transactional
    public UserKanbanCard saveKanbanCard(UserKanbanCard card) {
        if (card.getId() == null || card.getId().isEmpty()) {
            card.setId(UUID.randomUUID().toString());
        } else {
            requireOwner(kanbanRepository.findById(card.getId()).map(UserKanbanCard::getUserId), card.getUserId());
        }
        card.setUpdatedAt(LocalDateTime.now());
        return kanbanRepository.save(card);
    }

    @Transactional
    public void deleteKanbanCard(String id, String callerId) {
        requireOwner(kanbanRepository.findById(id).map(UserKanbanCard::getUserId), callerId);
        kanbanRepository.deleteById(id);
    }

    // --- Sticky Notes ---
    @Transactional(readOnly = true)
    public List<UserStickyNote> getStickyNotes(String userId) {
        return noteRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    @Transactional
    public UserStickyNote saveStickyNote(UserStickyNote note) {
        if (note.getId() == null || note.getId().isEmpty()) {
            note.setId(UUID.randomUUID().toString());
        } else {
            requireOwner(noteRepository.findById(note.getId()).map(UserStickyNote::getUserId), note.getUserId());
        }
        note.setUpdatedAt(LocalDateTime.now());
        return noteRepository.save(note);
    }

    @Transactional
    public void deleteStickyNote(String id, String callerId) {
        requireOwner(noteRepository.findById(id).map(UserStickyNote::getUserId), callerId);
        noteRepository.deleteById(id);
    }

    // --- Quick Links ---
    @Transactional(readOnly = true)
    public List<UserQuickLink> getQuickLinks(String userId) {
        return linkRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public UserQuickLink saveQuickLink(UserQuickLink link) {
        if (link.getId() == null || link.getId().isEmpty()) {
            link.setId(UUID.randomUUID().toString());
        } else {
            requireOwner(linkRepository.findById(link.getId()).map(UserQuickLink::getUserId), link.getUserId());
        }
        if (link.getCreatedAt() == null) {
            link.setCreatedAt(LocalDateTime.now());
        }
        return linkRepository.save(link);
    }

    @Transactional
    public void deleteQuickLink(String id, String callerId) {
        requireOwner(linkRepository.findById(id).map(UserQuickLink::getUserId), callerId);
        linkRepository.deleteById(id);
    }

    // --- Snippets ---
    @Transactional(readOnly = true)
    public List<UserSnippet> getSnippets(String userId) {
        return snippetRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public UserSnippet saveSnippet(UserSnippet snippet) {
        if (snippet.getId() == null || snippet.getId().isEmpty()) {
            snippet.setId(UUID.randomUUID().toString());
        } else {
            requireOwner(snippetRepository.findById(snippet.getId()).map(UserSnippet::getUserId), snippet.getUserId());
        }
        if (snippet.getCreatedAt() == null) {
            snippet.setCreatedAt(LocalDateTime.now());
        }
        return snippetRepository.save(snippet);
    }

    @Transactional
    public void deleteSnippet(String id, String callerId) {
        requireOwner(snippetRepository.findById(id).map(UserSnippet::getUserId), callerId);
        snippetRepository.deleteById(id);
    }

    /**
     * Id inexistente passa (save cria, delete é no-op); id de outro usuário é recusado — sem isso
     * um POST com o id de um card alheio "roubava" o card e o DELETE apagava o de qualquer um.
     */
    private static void requireOwner(Optional<String> existingOwner, String callerId) {
        if (existingOwner.isPresent() && (callerId == null || !callerId.equals(existingOwner.get()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Item pertence a outro usuário");
        }
    }
}
