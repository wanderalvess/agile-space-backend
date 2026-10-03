package com.agilespace.backend.controller;

import com.agilespace.backend.domain.Feedback;
import com.agilespace.backend.service.FeedbackService;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FeedbackController - Registro e Avaliação de Ferramentas")
class FeedbackControllerTest {

    @Mock
    private FeedbackService service;

    @InjectMocks
    private FeedbackController controller;

    @Nested
    @DisplayName("Envio de Feedback")
    class SubmitFeedbackTests {

        @Test
        @DisplayName("Deve delegar o salvamento ao service")
        void shouldDelegateSaveToService() {
            Feedback input = Feedback.builder()
                    .toolName("PlanningPoker")
                    .score(5)
                    .comment("Interface excelente e cálculo de consenso muito rápido!")
                    .userId("user-123")
                    .build();
            Feedback saved = Feedback.builder().id("f1").toolName("PlanningPoker").score(5).userId("user-123").build();

            when(service.saveFeedback(input)).thenReturn(saved);

            ResponseEntity<Feedback> response = controller.saveFeedback(input);

            assertEquals(saved, response.getBody());
            verify(service, times(1)).saveFeedback(input);
        }
    }

    @Nested
    @DisplayName("Listagem de Feedbacks")
    class ListFeedbackTests {

        @Test
        @DisplayName("Deve retornar todos os feedbacks vindos do service")
        void shouldReturnAllFeedbacksFromService() {
            Feedback f1 = Feedback.builder().id("1").toolName("FocusTimer").score(4).build();
            Feedback f2 = Feedback.builder().id("2").toolName("Showcase").score(5).build();

            when(service.getAllFeedbacks()).thenReturn(Arrays.asList(f1, f2));

            ResponseEntity<List<Feedback>> response = controller.getAllFeedbacks();
            List<Feedback> list = response.getBody();

            assertNotNull(list);
            assertEquals(2, list.size());
            verify(service, times(1)).getAllFeedbacks();
        }

        @Test
        @DisplayName("Deve filtrar feedbacks por status")
        void shouldReturnFeedbacksByStatus() {
            Feedback open = Feedback.builder().id("1").status("OPEN").build();
            when(service.getFeedbacksByStatus("OPEN")).thenReturn(List.of(open));

            ResponseEntity<List<Feedback>> response = controller.getFeedbacksByStatus("OPEN");

            assertEquals(1, response.getBody().size());
            verify(service, times(1)).getFeedbacksByStatus("OPEN");
        }
    }

    @Nested
    @DisplayName("Atualização e Exclusão")
    class MutateFeedbackTests {

        @Test
        @DisplayName("Deve atualizar o status quando o feedback existir")
        void shouldUpdateStatusWhenFeedbackExists() {
            Feedback updated = Feedback.builder().id("1").status("REVIEWED").build();
            when(service.updateFeedbackStatus("1", "REVIEWED")).thenReturn(Optional.of(updated));

            ResponseEntity<Feedback> response = controller.updateFeedbackStatus("1", "REVIEWED");

            assertEquals(updated, response.getBody());
        }

        @Test
        @DisplayName("Deve retornar 404 ao atualizar status de feedback inexistente")
        void shouldReturnNotFoundWhenUpdatingMissingFeedback() {
            when(service.updateFeedbackStatus("missing", "REVIEWED")).thenReturn(Optional.empty());

            ResponseEntity<Feedback> response = controller.updateFeedbackStatus("missing", "REVIEWED");

            assertEquals(404, response.getStatusCode().value());
        }

        @Test
        @DisplayName("Deve retornar 404 ao excluir feedback inexistente")
        void shouldReturnNotFoundWhenDeletingMissingFeedback() {
            when(service.deleteFeedback("missing")).thenReturn(false);

            ResponseEntity<Void> response = controller.deleteFeedback("missing");

            assertEquals(404, response.getStatusCode().value());
        }
    }
}
