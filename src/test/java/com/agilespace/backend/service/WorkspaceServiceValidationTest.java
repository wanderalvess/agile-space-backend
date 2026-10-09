package com.agilespace.backend.service;

import com.agilespace.backend.domain.UserKanbanCard;
import com.agilespace.backend.domain.UserQuickLink;
import com.agilespace.backend.domain.UserSnippet;
import com.agilespace.backend.domain.UserStickyNote;
import com.agilespace.backend.repository.UserKanbanCardRepository;
import com.agilespace.backend.repository.UserQuickLinkRepository;
import com.agilespace.backend.repository.UserSnippetRepository;
import com.agilespace.backend.repository.UserStickyNoteRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkspaceService - validação, escrita parcial e limites do Meu Espaço")
class WorkspaceServiceValidationTest {

    @Mock private UserKanbanCardRepository kanbanRepository;
    @Mock private UserStickyNoteRepository noteRepository;
    @Mock private UserQuickLinkRepository linkRepository;
    @Mock private UserSnippetRepository snippetRepository;

    @InjectMocks
    private WorkspaceService service;

    private static HttpStatus statusOf(Supplier<?> call) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, call::get);
        return HttpStatus.valueOf(ex.getStatusCode().value());
    }

    private UserKanbanCard existingCard() {
        return UserKanbanCard.builder().id("k1").userId("u1").title("Original").description("desc")
                .status("todo").priority("alta").tag("Retro").originLink("/retro/abc")
                .exportedAt(LocalDateTime.of(2026, 1, 1, 0, 0)).updatedAt(LocalDateTime.now()).build();
    }

    @Test
    @DisplayName("PATCH do kanban muda só o status e preserva título, descrição, origem e prioridade")
    void patchKanbanKeepsOtherFields() {
        UserKanbanCard card = existingCard();
        when(kanbanRepository.findById("k1")).thenReturn(Optional.of(card));
        when(kanbanRepository.save(any(UserKanbanCard.class))).thenAnswer(i -> i.getArgument(0));

        UserKanbanCard saved = service.patchKanbanCard("k1", "u1", Map.of("status", "doing"));

        assertEquals("doing", saved.getStatus());
        assertEquals("Original", saved.getTitle());
        assertEquals("desc", saved.getDescription());
        assertEquals("alta", saved.getPriority());
        assertEquals("/retro/abc", saved.getOriginLink());
    }

    @Test
    @DisplayName("PATCH com null explícito limpa a descrição e o prazo")
    void patchKanbanNullClears() {
        UserKanbanCard card = existingCard();
        card.setDueDate(LocalDateTime.now());
        when(kanbanRepository.findById("k1")).thenReturn(Optional.of(card));
        when(kanbanRepository.save(any(UserKanbanCard.class))).thenAnswer(i -> i.getArgument(0));
        Map<String, Object> changes = new HashMap<>();
        changes.put("description", null);
        changes.put("dueDate", null);

        UserKanbanCard saved = service.patchKanbanCard("k1", "u1", changes);

        assertNull(saved.getDescription());
        assertNull(saved.getDueDate());
    }

    @Test
    @DisplayName("PATCH de card alheio é 403 e inexistente é 404")
    void patchKanbanAuthorization() {
        when(kanbanRepository.findById("k1")).thenReturn(Optional.of(existingCard()));
        assertEquals(HttpStatus.FORBIDDEN, statusOf(() -> service.patchKanbanCard("k1", "u2", Map.of("status", "done"))));
        verify(kanbanRepository, never()).save(any());

        when(kanbanRepository.findById("nope")).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, statusOf(() -> service.patchKanbanCard("nope", "u1", Map.of("status", "done"))));
    }

    @Test
    @DisplayName("PATCH recusa status e prioridade fora da lista e título vazio")
    void patchKanbanValidates() {
        when(kanbanRepository.findById("k1")).thenReturn(Optional.of(existingCard()));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.patchKanbanCard("k1", "u1", Map.of("status", "arquivado"))));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.patchKanbanCard("k1", "u1", Map.of("priority", "urgentissima"))));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.patchKanbanCard("k1", "u1", Map.of("title", "   "))));
    }

    @Test
    @DisplayName("POST de card novo sem título é 400 e aplica status/prioridade padrão")
    void saveKanbanDefaultsAndRequiresTitle() {
        UserKanbanCard noTitle = UserKanbanCard.builder().userId("u1").title("  ").build();
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.saveKanbanCard(noTitle)));

        when(kanbanRepository.save(any(UserKanbanCard.class))).thenAnswer(i -> i.getArgument(0));
        UserKanbanCard saved = service.saveKanbanCard(UserKanbanCard.builder().userId("u1").title(" Tarefa ").build());
        assertEquals("Tarefa", saved.getTitle());
        assertEquals("todo", saved.getStatus());
        assertEquals("media", saved.getPriority());
    }

    @Test
    @DisplayName("POST sobre card existente não apaga origem, exportação e etiqueta")
    void saveKanbanKeepsOrigin() {
        when(kanbanRepository.findById("k1")).thenReturn(Optional.of(existingCard()));
        when(kanbanRepository.save(any(UserKanbanCard.class))).thenAnswer(i -> i.getArgument(0));

        UserKanbanCard edit = UserKanbanCard.builder().id("k1").userId("u1").title("Novo título").status("doing").priority("alta").build();
        UserKanbanCard saved = service.saveKanbanCard(edit);

        assertEquals("/retro/abc", saved.getOriginLink());
        assertNotNull(saved.getExportedAt());
        assertEquals("Retro", saved.getTag());
    }

    @Test
    @DisplayName("Link de origem aceita caminho interno e http(s) e recusa javascript:, // e data:")
    void originLinkValidation() {
        assertEquals("/retro/abc", WorkspaceService.normalizeOriginLink("/retro/abc"));
        assertEquals("https://x.com/a", WorkspaceService.normalizeOriginLink("https://x.com/a"));
        assertNull(WorkspaceService.normalizeOriginLink("  "));
        for (String bad : new String[]{"javascript:alert(1)", "//evil.com", "data:text/html,x", "/\\evil.com"}) {
            assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> WorkspaceService.normalizeOriginLink(bad)), bad);
        }
    }

    @Test
    @DisplayName("Atalho só aceita http/https e exige nome")
    void quickLinkValidation() {
        for (String bad : new String[]{"javascript:alert(1)", "ftp://x.com", "data:text/html;base64,AAA", "https://", "naoeurl"}) {
            UserQuickLink link = UserQuickLink.builder().userId("u1").title("Jira").url(bad).build();
            assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.saveQuickLink(link)), bad);
        }
        UserQuickLink noName = UserQuickLink.builder().userId("u1").title(" ").url("https://a.com").build();
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.saveQuickLink(noName)));

        when(linkRepository.save(any(UserQuickLink.class))).thenAnswer(i -> i.getArgument(0));
        UserQuickLink saved = service.saveQuickLink(UserQuickLink.builder().userId("u1").title("Jira").url(" https://jira.x.com/b ")
                .iconType("book").color("text-blue-500 bg-blue-50").build());
        assertEquals("https://jira.x.com/b", saved.getUrl());
        assertEquals("book", saved.getIconType());
        assertNotNull(saved.getId());
    }

    @Test
    @DisplayName("Limite por usuário: card novo além de 2000 é 409")
    void capacityLimit() {
        when(kanbanRepository.countByUserId("u1")).thenReturn((long) WorkspaceService.MAX_ITEMS_PER_USER);
        UserKanbanCard card = UserKanbanCard.builder().userId("u1").title("x").build();
        assertEquals(HttpStatus.CONFLICT, statusOf(() -> service.saveKanbanCard(card)));
        verify(kanbanRepository, never()).save(any());
    }

    @Test
    @DisplayName("PATCH da nota muda só o que veio (fixar não zera texto nem cor)")
    void patchNoteKeepsContent() {
        UserStickyNote note = UserStickyNote.builder().id("n1").userId("u1").content("texto").color("bg-sky").updatedAt(LocalDateTime.now()).build();
        when(noteRepository.findById("n1")).thenReturn(Optional.of(note));
        when(noteRepository.save(any(UserStickyNote.class))).thenAnswer(i -> i.getArgument(0));

        UserStickyNote saved = service.patchStickyNote("n1", "u1", Map.of("isPinned", true));

        assertTrue(saved.isPinned());
        assertEquals("texto", saved.getContent());
        assertEquals("bg-sky", saved.getColor());
        assertEquals(HttpStatus.FORBIDDEN, statusOf(() -> service.patchStickyNote("n1", "u2", Map.of("content", "x"))));
    }

    @Test
    @DisplayName("Nota nova vazia ganha cor padrão e texto vazio (não nulo)")
    void newEmptyNote() {
        when(noteRepository.save(any(UserStickyNote.class))).thenAnswer(i -> i.getArgument(0));
        UserStickyNote saved = service.saveStickyNote(UserStickyNote.builder().userId("u1").build());
        assertEquals("", saved.getContent());
        assertEquals(WorkspaceService.DEFAULT_NOTE_COLOR, saved.getColor());
    }

    @Test
    @DisplayName("Nota acima do limite de texto é recusada")
    void noteTooLong() {
        UserStickyNote note = UserStickyNote.builder().userId("u1").content("a".repeat(WorkspaceService.MAX_NOTE + 1)).build();
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.saveStickyNote(note)));
    }

    @Test
    @DisplayName("Snippet exige título e respeita o limite de código")
    void snippetValidation() {
        UserSnippet noTitle = UserSnippet.builder().userId("u1").title("").build();
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.saveSnippet(noTitle)));
        UserSnippet big = UserSnippet.builder().userId("u1").title("x").content("a".repeat(WorkspaceService.MAX_SNIPPET + 1)).build();
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.saveSnippet(big)));
    }

    @Test
    @DisplayName("JSON da nota usa isPinned (e aceita pinned) em vez de só 'pinned'")
    void noteJsonUsesIsPinned() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        UserStickyNote note = UserStickyNote.builder().id("n1").userId("u1").content("c").color("x").updatedAt(LocalDateTime.now()).build();
        note.setPinned(true);
        String json = mapper.writeValueAsString(note);
        assertTrue(json.contains("\"isPinned\":true"), json);
        assertFalse(json.contains("\"pinned\""), json);

        UserStickyNote readBack = mapper.readValue("{\"id\":\"n1\",\"content\":\"c\",\"color\":\"x\",\"isPinned\":true}", UserStickyNote.class);
        assertTrue(readBack.isPinned());
        UserStickyNote legacy = mapper.readValue("{\"id\":\"n1\",\"content\":\"c\",\"color\":\"x\",\"pinned\":true}", UserStickyNote.class);
        assertTrue(legacy.isPinned());
    }
}
