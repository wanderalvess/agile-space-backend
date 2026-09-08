package com.agilespace.backend.controller;

import com.agilespace.backend.domain.Feedback;
import com.agilespace.backend.repository.FeedbackRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FeedbackController - Registro e Avaliação de Ferramentas")
class FeedbackControllerTest {

    @Mock
    private FeedbackRepository repository;

    @InjectMocks
    private FeedbackController controller;

    @Nested
    @DisplayName("Envio de Feedback")
    class SubmitFeedbackTests {

        @Test
        @DisplayName("Deve salvar feedback gerando ID e data de criação automaticamente")
        void shouldSaveFeedbackWithGeneratedIdAndTimestamp() {
            Feedback feedback = Feedback.builder()
                    .toolName("PlanningPoker")
                    .score(5)
                    .comment("Interface excelente e cálculo de consenso muito rápido!")
                    .userId("user-123")
                    .build();

            when(repository.save(any(Feedback.class))).thenAnswer(i -> i.getArgument(0));

            ResponseEntity<Feedback> response = controller.saveFeedback(feedback);
            Feedback saved = response.getBody();

            assertNotNull(saved);
            assertNotNull(saved.getId(), "ID deve ser gerado pelo controller");
            assertNotNull(saved.getCreatedAt(), "CreatedAt deve ser preenchido");
            assertEquals("PlanningPoker", saved.getToolName());
            assertEquals(5, saved.getScore());
            assertEquals("user-123", saved.getUserId());
            verify(repository, times(1)).save(feedback);
        }
    }

    @Nested
    @DisplayName("Listagem de Feedbacks")
    class ListFeedbackTests {

        @Test
        @DisplayName("Deve retornar todos os feedbacks ordenados pela data de criação decrescente")
        void shouldReturnAllFeedbacksOrderedByCreatedAtDesc() {
            Feedback f1 = Feedback.builder().id("1").toolName("FocusTimer").score(4).build();
            Feedback f2 = Feedback.builder().id("2").toolName("Showcase").score(5).build();

            when(repository.findByOrderByCreatedAtDesc()).thenReturn(Arrays.asList(f1, f2));

            ResponseEntity<List<Feedback>> response = controller.getAllFeedbacks();
            List<Feedback> list = response.getBody();

            assertNotNull(list);
            assertEquals(2, list.size());
            assertEquals("1", list.get(0).getId());
            assertEquals("2", list.get(1).getId());
            verify(repository, times(1)).findByOrderByCreatedAtDesc();
        }
    }
}
