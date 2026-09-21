package com.agilespace.backend.service;

import com.agilespace.backend.domain.Feedback;
import com.agilespace.backend.repository.FeedbackRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FeedbackService - Registro e Avaliação de Ferramentas")
class FeedbackServiceTest {

    @Mock
    private FeedbackRepository repository;

    @InjectMocks
    private FeedbackService service;

    @Nested
    @DisplayName("Salvamento")
    class SaveTests {

        @Test
        @DisplayName("Deve gerar ID e data de criação ao salvar")
        void shouldGenerateIdAndTimestamp() {
            Feedback feedback = Feedback.builder()
                    .toolName("PlanningPoker")
                    .score(5)
                    .comment("Interface excelente!")
                    .userId("user-123")
                    .build();

            when(repository.save(any(Feedback.class))).thenAnswer(i -> i.getArgument(0));

            Feedback saved = service.saveFeedback(feedback);

            assertNotNull(saved.getId());
            assertNotNull(saved.getCreatedAt());
            assertEquals("PlanningPoker", saved.getToolName());
            verify(repository, times(1)).save(feedback);
        }
    }

    @Nested
    @DisplayName("Consulta")
    class QueryTests {

        @Test
        @DisplayName("Deve listar todos ordenados por data de criação")
        void shouldListAllOrderedByCreatedAtDesc() {
            Feedback f1 = Feedback.builder().id("1").toolName("FocusTimer").build();
            Feedback f2 = Feedback.builder().id("2").toolName("Showcase").build();
            when(repository.findByOrderByCreatedAtDesc()).thenReturn(Arrays.asList(f1, f2));

            List<Feedback> result = service.getAllFeedbacks();

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("Deve filtrar por status")
        void shouldFilterByStatus() {
            Feedback open = Feedback.builder().id("1").status("OPEN").build();
            when(repository.findByStatus("OPEN")).thenReturn(List.of(open));

            List<Feedback> result = service.getFeedbacksByStatus("OPEN");

            assertEquals(1, result.size());
        }
    }

    @Nested
    @DisplayName("Atualização e Exclusão")
    class MutateTests {

        @Test
        @DisplayName("Deve atualizar status quando o feedback existir")
        void shouldUpdateStatusWhenExists() {
            Feedback existing = Feedback.builder().id("1").status("OPEN").build();
            when(repository.findById("1")).thenReturn(Optional.of(existing));
            when(repository.save(any(Feedback.class))).thenAnswer(i -> i.getArgument(0));

            Optional<Feedback> result = service.updateFeedbackStatus("1", "REVIEWED");

            assertTrue(result.isPresent());
            assertEquals("REVIEWED", result.get().getStatus());
        }

        @Test
        @DisplayName("Deve retornar vazio ao atualizar status de feedback inexistente")
        void shouldReturnEmptyWhenUpdatingMissingFeedback() {
            when(repository.findById("missing")).thenReturn(Optional.empty());

            Optional<Feedback> result = service.updateFeedbackStatus("missing", "REVIEWED");

            assertTrue(result.isEmpty());
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("Deve excluir quando o feedback existir")
        void shouldDeleteWhenExists() {
            when(repository.existsById("1")).thenReturn(true);

            boolean deleted = service.deleteFeedback("1");

            assertTrue(deleted);
            verify(repository, times(1)).deleteById("1");
        }

        @Test
        @DisplayName("Não deve excluir feedback inexistente")
        void shouldNotDeleteMissingFeedback() {
            when(repository.existsById("missing")).thenReturn(false);

            boolean deleted = service.deleteFeedback("missing");

            assertFalse(deleted);
            verify(repository, never()).deleteById(anyString());
        }
    }
}
