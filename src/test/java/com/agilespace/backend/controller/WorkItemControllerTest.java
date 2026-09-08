package com.agilespace.backend.controller;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.domain.WorkItem;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.WorkItemService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkItemController - Controle de Estimativas, Commits e Decisões de Showcase por Squad")
class WorkItemControllerTest {

    @Mock
    private WorkItemService workItemService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private WorkItemController controller;

    private HttpServletRequest requestAs(String userId, String role) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn(userId);
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE)).thenReturn(role);
        return request;
    }

    /** Membro da squad SQ1: passa no requireSquadWriteAccess. */
    private HttpServletRequest memberOfSq1() {
        User caller = User.builder().id("u1").email("joao@empresa.com.br").squadId("SQ1").build();
        lenient().when(userRepository.findById("u1")).thenReturn(Optional.of(caller));
        return requestAs("u1", "MEMBER");
    }

    /** Membro de outra squad: deve ser barrado ao mexer em work_items da SQ1. */
    private HttpServletRequest memberOfAnotherSquad() {
        User caller = User.builder().id("u2").email("outro@empresa.com.br").squadId("SQ-OUTRA").build();
        lenient().when(userRepository.findById("u2")).thenReturn(Optional.of(caller));
        return requestAs("u2", "MEMBER");
    }

    @Nested
    @DisplayName("Estimativa de Pontos (Planning Poker Integration)")
    class EstimateTests {

        @Test
        @DisplayName("Deve permitir ao membro da squad salvar pontuação estimada com sucesso")
        void shouldDelegateEstimateWhenAuthorized() {
            ResponseEntity<Void> response = controller.estimateWorkItem(
                    "SQ1", "DDW-1", new WorkItemController.EstimateRequest(8.0), memberOfSq1());

            assertEquals(HttpStatus.OK, response.getStatusCode());
            verify(workItemService).estimateWorkItem("SQ1", "DDW-1", 8.0);
        }

        @Test
        @DisplayName("Deve aceitar limpeza de estimativa passando pontos nulos")
        void shouldAcceptNullEstimatePoints() {
            ResponseEntity<Void> response = controller.estimateWorkItem(
                    "SQ1", "DDW-1", new WorkItemController.EstimateRequest(null), memberOfSq1());

            assertEquals(HttpStatus.OK, response.getStatusCode());
            verify(workItemService).estimateWorkItem("SQ1", "DDW-1", null);
        }
    }

    @Nested
    @DisplayName("Compromisso de Sprint e Decisão de Showcase")
    class CommitAndShowcaseTests {

        @Test
        @DisplayName("Deve delegar commit de item para a sprint alvo")
        void shouldCommitWorkItemToSprint() {
            ResponseEntity<Void> response = controller.commitWorkItem(
                    "SQ1", "DDW-1", new WorkItemController.CommitRequest("SPRINT-42"), memberOfSq1());

            assertEquals(HttpStatus.OK, response.getStatusCode());
            verify(workItemService).commitWorkItem("SQ1", "DDW-1", "SPRINT-42");
        }

        @Test
        @DisplayName("Deve delegar decisão de aceite/rejeição no showcase com feedback")
        void shouldRecordShowcaseDecisionWithFeedback() {
            ResponseEntity<Void> response = controller.showcaseDecision(
                    "SQ1", "DDW-1", new WorkItemController.ShowcaseDecisionRequest("delivered", "Aceito pelo PO"), memberOfSq1());

            assertEquals(HttpStatus.OK, response.getStatusCode());
            verify(workItemService).showcaseDecision("SQ1", "DDW-1", "delivered", "Aceito pelo PO");
        }

        @Test
        @DisplayName("Deve retornar estatísticas completas de velocidade e carry-over da sprint")
        void shouldReturnSprintVelocityStats() {
            Map<String, Object> stats = Map.of("velocityReal", 8.0, "previsto", 13.0, "entregue", 8.0, "carryOvers", 1);
            when(workItemService.getSprintStats("SQ1", "SPRINT-42")).thenReturn(stats);

            ResponseEntity<Map<String, Object>> response = controller.getSprintStats("SQ1", "SPRINT-42");

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(8.0, response.getBody().get("velocityReal"));
        }
    }

    @Nested
    @DisplayName("Segurança e Controle de Acesso por Squad (requireSquadWriteAccess)")
    class AuthorizationTests {

        @Test
        @DisplayName("Deve rejeitar com HTTP 403 tentativa de estimar item por membro de outra squad")
        void shouldRejectEstimateFromAnotherSquadMember() {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.estimateWorkItem(
                    "SQ1", "DDW-1", new WorkItemController.EstimateRequest(8.0), memberOfAnotherSquad()));

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
            verifyNoInteractions(workItemService);
        }

        @Test
        @DisplayName("Deve rejeitar com HTTP 403 tentativa de comitar item por membro de outra squad")
        void shouldRejectCommitFromAnotherSquadMember() {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.commitWorkItem(
                    "SQ1", "DDW-1", new WorkItemController.CommitRequest("SPRINT-42"), memberOfAnotherSquad()));

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
            verifyNoInteractions(workItemService);
        }

        @Test
        @DisplayName("Deve rejeitar com HTTP 403 decisão de showcase por membro de outra squad")
        void shouldRejectShowcaseDecisionFromAnotherSquadMember() {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.showcaseDecision(
                    "SQ1", "DDW-1", new WorkItemController.ShowcaseDecisionRequest("delivered", "Aceito"),
                    memberOfAnotherSquad()));

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
            verifyNoInteractions(workItemService);
        }

        @Test
        @DisplayName("Deve rejeitar com HTTP 403 usuário que ainda não possui squad vinculada")
        void shouldRejectUserWithoutSquad() {
            User caller = User.builder().id("u3").email("sem.squad@empresa.com.br").build();
            when(userRepository.findById("u3")).thenReturn(Optional.of(caller));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.estimateWorkItem(
                    "SQ1", "DDW-1", new WorkItemController.EstimateRequest(8.0), requestAs("u3", "MEMBER")));

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        }

        @Test
        @DisplayName("Deve permitir ao ADMIN escrita em qualquer squad sem precisar consultar banco de usuários")
        void shouldAllowAdminToWriteToAnySquad() {
            ResponseEntity<Void> response = controller.estimateWorkItem(
                    "SQ-QUALQUER", "DDW-1", new WorkItemController.EstimateRequest(8.0), requestAs("admin1", "ADMIN"));

            assertEquals(HttpStatus.OK, response.getStatusCode());
            verify(workItemService).estimateWorkItem("SQ-QUALQUER", "DDW-1", 8.0);
            verifyNoInteractions(userRepository);
        }

        @Test
        @DisplayName("Deve permitir ao LEAD escrita em qualquer squad")
        void shouldAllowLeadToWriteToAnySquad() {
            ResponseEntity<Void> response = controller.commitWorkItem(
                    "SQ-QUALQUER", "DDW-1", new WorkItemController.CommitRequest("SPRINT-42"), requestAs("lead1", "LEAD"));

            assertEquals(HttpStatus.OK, response.getStatusCode());
            verify(workItemService).commitWorkItem("SQ-QUALQUER", "DDW-1", "SPRINT-42");
        }
    }
}
