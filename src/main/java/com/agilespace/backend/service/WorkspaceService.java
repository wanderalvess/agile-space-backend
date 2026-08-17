package com.agilespace.backend.service;

import com.agilespace.backend.domain.UserKanbanCard;
import com.agilespace.backend.domain.UserStickyNote;
import com.agilespace.backend.domain.UserQuickLink;
import com.agilespace.backend.repository.UserKanbanCardRepository;
import com.agilespace.backend.repository.UserStickyNoteRepository;
import com.agilespace.backend.repository.UserQuickLinkRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class WorkspaceService {

    @Autowired
    private UserKanbanCardRepository kanbanRepository;

    @Autowired
    private UserStickyNoteRepository noteRepository;

    @Autowired
    private UserQuickLinkRepository linkRepository;

    // --- Kanban ---
    @Transactional(readOnly = true)
    public List<UserKanbanCard> getKanbanCards(String userId) {
        return kanbanRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    @Transactional
    public UserKanbanCard saveKanbanCard(UserKanbanCard card) {
        if (card.getId() == null || card.getId().isEmpty()) {
            card.setId(UUID.randomUUID().toString());
        }
        card.setUpdatedAt(LocalDateTime.now());
        return kanbanRepository.save(card);
    }

    @Transactional
    public void deleteKanbanCard(String id) {
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
        }
        note.setUpdatedAt(LocalDateTime.now());
        return noteRepository.save(note);
    }

    @Transactional
    public void deleteStickyNote(String id) {
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
        }
        if (link.getCreatedAt() == null) {
            link.setCreatedAt(LocalDateTime.now());
        }
        return linkRepository.save(link);
    }

    @Transactional
    public void deleteQuickLink(String id) {
        linkRepository.deleteById(id);
    }
}
