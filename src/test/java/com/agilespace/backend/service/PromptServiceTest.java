package com.agilespace.backend.service;

import com.agilespace.backend.domain.Prompt;
import com.agilespace.backend.domain.PromptComment;
import com.agilespace.backend.repository.PromptCommentRepository;
import com.agilespace.backend.repository.PromptRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PromptService - Biblioteca de Prompts de IA, Compartilhamento e Métricas de Uso")
class PromptServiceTest {

    @Mock private PromptRepository promptRepository;
    @Mock private PromptCommentRepository commentRepository;

    @InjectMocks
    private PromptService service;

    @Nested
    @DisplayName("Listagem e Busca de Prompts")
    class SearchTests {

        @Test
        @DisplayName("Deve buscar prompts públicos por termo de pesquisa")
        void shouldSearchPublicPromptsByQuery() {
            Page<Prompt> page = new PageImpl<>(Collections.singletonList(new Prompt()));
            when(promptRepository.searchPublic(eq("scrum"), eq("public"), any(Pageable.class))).thenReturn(page);

            Page<Prompt> result = service.listPrompts("scrum", null, PageRequest.of(0, 10));

            assertEquals(1, result.getTotalElements());
            verify(promptRepository).searchPublic(eq("scrum"), eq("public"), any(Pageable.class));
        }

        @Test
        @DisplayName("Deve listar prompts por autor específico")
        void shouldListPromptsByAuthor() {
            Page<Prompt> page = new PageImpl<>(Collections.singletonList(new Prompt()));
            when(promptRepository.findByAuthorId(eq("user-123"), any(Pageable.class))).thenReturn(page);

            Page<Prompt> result = service.listPrompts(null, "user-123", PageRequest.of(0, 10));

            assertEquals(1, result.getTotalElements());
            verify(promptRepository).findByAuthorId(eq("user-123"), any(Pageable.class));
        }
    }

    @Nested
    @DisplayName("Criação, Métricas e Comentários")
    class MutationTests {

        @Test
        @DisplayName("Deve inicializar contadores de uso e fork zerados ao criar novo prompt")
        void shouldResetCountersOnCreate() {
            Prompt prompt = Prompt.builder().title("Prompt de Refatoração").useCount(99).forkCount(12).build();
            when(promptRepository.save(any(Prompt.class))).thenAnswer(i -> i.getArgument(0));

            Prompt saved = service.createPrompt(prompt);

            assertEquals(0, saved.getUseCount(), "Contador de uso deve iniciar em zero");
            assertEquals(0, saved.getForkCount(), "Contador de fork deve iniciar em zero");
        }

        @Test
        @DisplayName("Deve incrementar contagem de utilização do prompt")
        void shouldIncrementUseCount() {
            UUID promptId = UUID.randomUUID();
            Prompt prompt = Prompt.builder().id(promptId).useCount(10).build();
            when(promptRepository.findById(promptId)).thenReturn(Optional.of(prompt));
            when(promptRepository.save(any(Prompt.class))).thenAnswer(i -> i.getArgument(0));

            Prompt updated = service.incrementUseCount(promptId);

            assertEquals(11, updated.getUseCount());
        }

        @Test
        @DisplayName("Deve excluir prompt e remover em cascata os comentários vinculados")
        void shouldDeletePromptAndComments() {
            UUID promptId = UUID.randomUUID();
            when(commentRepository.findByPromptIdOrderByCreatedAtAsc(promptId))
                    .thenReturn(Collections.singletonList(new PromptComment()));

            service.deletePrompt(promptId);

            verify(commentRepository).deleteAll(any());
            verify(promptRepository).deleteById(promptId);
        }

        @Test
        @DisplayName("Deve adicionar comentário ao prompt com vínculo bidirecional")
        void shouldAddCommentToPrompt() {
            UUID promptId = UUID.randomUUID();
            Prompt prompt = Prompt.builder().id(promptId).build();
            when(promptRepository.findById(promptId)).thenReturn(Optional.of(prompt));

            PromptComment comment = new PromptComment();
            when(commentRepository.save(any(PromptComment.class))).thenAnswer(i -> i.getArgument(0));

            PromptComment saved = service.addComment(promptId, comment);

            assertNotNull(saved.getPrompt());
            assertEquals(promptId, saved.getPrompt().getId());
        }
    }
}
