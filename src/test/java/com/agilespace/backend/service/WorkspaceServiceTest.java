package com.agilespace.backend.service;

import com.agilespace.backend.domain.UserKanbanCard;
import com.agilespace.backend.domain.UserQuickLink;
import com.agilespace.backend.domain.UserStickyNote;
import com.agilespace.backend.repository.UserKanbanCardRepository;
import com.agilespace.backend.repository.UserQuickLinkRepository;
import com.agilespace.backend.repository.UserStickyNoteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkspaceService - Gestão do Espaço Pessoal (Kanban Individual, Sticky Notes, Quick Links)")
class WorkspaceServiceTest {

    @Mock private UserKanbanCardRepository kanbanRepository;
    @Mock private UserStickyNoteRepository noteRepository;
    @Mock private UserQuickLinkRepository linkRepository;

    @InjectMocks
    private WorkspaceService service;

    @Nested
    @DisplayName("Quadro Kanban Individual")
    class KanbanTests {

        @Test
        @DisplayName("Deve listar cards do kanban do usuário ordenados por data de atualização decrescente")
        void shouldListKanbanCards() {
            UserKanbanCard card1 = UserKanbanCard.builder().id("1").userId("user-123").title("Revisar PR #42").build();
            UserKanbanCard card2 = UserKanbanCard.builder().id("2").userId("user-123").title("Criar testes e2e").build();

            when(kanbanRepository.findByUserIdOrderByUpdatedAtDesc("user-123"))
                    .thenReturn(Arrays.asList(card1, card2));

            List<UserKanbanCard> cards = service.getKanbanCards("user-123");

            assertEquals(2, cards.size());
            assertEquals("Revisar PR #42", cards.get(0).getTitle());
            verify(kanbanRepository).findByUserIdOrderByUpdatedAtDesc("user-123");
        }

        @Test
        @DisplayName("Deve salvar cartão kanban gerando ID e timestamp de atualização")
        void shouldSaveKanbanCard() {
            UserKanbanCard card = UserKanbanCard.builder().userId("user-123").title("Card").build();
            when(kanbanRepository.save(any(UserKanbanCard.class))).thenAnswer(i -> i.getArgument(0));

            UserKanbanCard saved = service.saveKanbanCard(card);

            assertNotNull(saved.getId());
            assertNotNull(saved.getUpdatedAt());
        }

        @Test
        @DisplayName("Deve excluir cartão kanban pelo identificador")
        void shouldDeleteKanbanCard() {
            service.deleteKanbanCard("k1");
            verify(kanbanRepository).deleteById("k1");
        }
    }

    @Nested
    @DisplayName("Notas Autoadesivas (Sticky Notes)")
    class StickyNoteTests {

        @Test
        @DisplayName("Deve salvar nota adesiva gerando ID")
        void shouldSaveStickyNote() {
            UserStickyNote note = UserStickyNote.builder()
                    .userId("user-123")
                    .content("Lembrar de rodar migrações do Liquibase")
                    .color("bg-yellow-50")
                    .build();

            when(noteRepository.save(any(UserStickyNote.class))).thenAnswer(i -> i.getArgument(0));

            UserStickyNote saved = service.saveStickyNote(note);

            assertNotNull(saved.getId());
            assertEquals("Lembrar de rodar migrações do Liquibase", saved.getContent());
            verify(noteRepository).save(note);
        }

        @Test
        @DisplayName("Deve excluir nota adesiva")
        void shouldDeleteStickyNote() {
            service.deleteStickyNote("n1");
            verify(noteRepository).deleteById("n1");
        }
    }

    @Nested
    @DisplayName("Links Rápidos (Quick Links)")
    class QuickLinkTests {

        @Test
        @DisplayName("Deve listar links rápidos salvos do usuário")
        void shouldListQuickLinks() {
            when(linkRepository.findByUserIdOrderByCreatedAtDesc("user-123"))
                    .thenReturn(Collections.singletonList(new UserQuickLink()));

            List<UserQuickLink> result = service.getQuickLinks("user-123");

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("Deve salvar link rápido gerando data de criação")
        void shouldSaveQuickLink() {
            UserQuickLink link = UserQuickLink.builder()
                    .userId("user-123")
                    .title("Repositório Frontend")
                    .url("https://github.com/totvs/agile-space-frontend")
                    .build();

            when(linkRepository.save(any(UserQuickLink.class))).thenAnswer(i -> i.getArgument(0));

            UserQuickLink saved = service.saveQuickLink(link);

            assertNotNull(saved.getCreatedAt());
            assertEquals("Repositório Frontend", saved.getTitle());
            verify(linkRepository).save(link);
        }

        @Test
        @DisplayName("Deve excluir link rápido")
        void shouldDeleteQuickLink() {
            service.deleteQuickLink("l1");
            verify(linkRepository).deleteById("l1");
        }
    }
}
