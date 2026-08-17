package com.agilespace.backend;

import com.agilespace.backend.domain.UserKanbanCard;
import com.agilespace.backend.domain.UserStickyNote;
import com.agilespace.backend.domain.UserQuickLink;
import com.agilespace.backend.repository.UserKanbanCardRepository;
import com.agilespace.backend.repository.UserStickyNoteRepository;
import com.agilespace.backend.repository.UserQuickLinkRepository;
import com.agilespace.backend.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class WorkspaceServiceTest {

    @Mock
    private UserKanbanCardRepository kanbanRepository;

    @Mock
    private UserStickyNoteRepository noteRepository;

    @Mock
    private UserQuickLinkRepository linkRepository;

    @InjectMocks
    private WorkspaceService service;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    // --- Kanban ---
    @Test
    public void testGetKanbanCardsByUserId() {
        UserKanbanCard card1 = UserKanbanCard.builder().id("1").userId("user-123").title("Task 1").build();
        UserKanbanCard card2 = UserKanbanCard.builder().id("2").userId("user-123").title("Task 2").build();

        when(kanbanRepository.findByUserIdOrderByUpdatedAtDesc("user-123"))
                .thenReturn(Arrays.asList(card1, card2));

        List<UserKanbanCard> cards = service.getKanbanCards("user-123");

        assertEquals(2, cards.size());
        assertEquals("Task 1", cards.get(0).getTitle());
        verify(kanbanRepository, times(1)).findByUserIdOrderByUpdatedAtDesc("user-123");
    }

    @Test
    public void testSaveKanbanCard() {
        UserKanbanCard card = UserKanbanCard.builder().build();
        when(kanbanRepository.save(any(UserKanbanCard.class))).thenAnswer(i -> i.getArgument(0));

        UserKanbanCard saved = service.saveKanbanCard(card);
        assertNotNull(saved.getId());
        assertNotNull(saved.getUpdatedAt());
    }

    @Test
    public void testDeleteKanbanCard() {
        service.deleteKanbanCard("k1");
        verify(kanbanRepository).deleteById("k1");
    }

    // --- Sticky Notes ---
    @Test
    public void testGetStickyNotes() {
        when(noteRepository.findByUserIdOrderByUpdatedAtDesc("u1")).thenReturn(Arrays.asList(new UserStickyNote()));
        List<UserStickyNote> result = service.getStickyNotes("u1");
        assertEquals(1, result.size());
    }

    @Test
    public void testSaveStickyNoteGeneratesId() {
        UserStickyNote note = UserStickyNote.builder()
                .userId("user-123")
                .content("Note content")
                .color("bg-yellow-50")
                .build();

        when(noteRepository.save(any(UserStickyNote.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserStickyNote saved = service.saveStickyNote(note);

        assertNotNull(saved.getId());
        assertFalse(saved.getId().isEmpty());
        assertEquals("Note content", saved.getContent());
        verify(noteRepository, times(1)).save(note);
    }

    @Test
    public void testDeleteStickyNote() {
        service.deleteStickyNote("n1");
        verify(noteRepository).deleteById("n1");
    }

    // --- Quick Links ---
    @Test
    public void testGetQuickLinks() {
        when(linkRepository.findByUserIdOrderByCreatedAtDesc("u1")).thenReturn(Arrays.asList(new UserQuickLink()));
        List<UserQuickLink> result = service.getQuickLinks("u1");
        assertEquals(1, result.size());
    }

    @Test
    public void testSaveQuickLinkSetsCreatedAt() {
        UserQuickLink link = UserQuickLink.builder()
                .userId("user-123")
                .title("My Link")
                .url("https://example.com")
                .build();

        when(linkRepository.save(any(UserQuickLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserQuickLink saved = service.saveQuickLink(link);

        assertNotNull(saved.getCreatedAt());
        assertEquals("My Link", saved.getTitle());
        verify(linkRepository, times(1)).save(link);
    }

    @Test
    public void testDeleteQuickLink() {
        service.deleteQuickLink("l1");
        verify(linkRepository).deleteById("l1");
    }
}
