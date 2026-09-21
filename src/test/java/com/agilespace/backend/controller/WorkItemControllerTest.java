package com.agilespace.backend.controller;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.domain.WorkItem;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
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

    @Mock
    private ProjectMemberRoleRepository projectMemberRoleRepository;

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
        // requireSquadWriteAccess consulta ProjectMemberRoleRepository como fallback
        // (passo 2.1) antes de decidir — sem papel de projeto pra essa squad.
        lenient().when(projectMemberRoleRepository.findByEmailIgnoreCase("outro@empresa.com.br")).thenReturn(List.of());
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

            ResponseEntity<Map<String, Object>> response = controller.getSprintStats("SQ1", "SPRINT-42", memberOfSq1());

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
        @DisplayName("Não deve permitir que jobTitle autodeclarado de liderança destrave escrita em squad alheia")
        void shouldRejectSelfDeclaredLeadershipJobTitleFromAnotherSquadMember() {
            // Regressão: requireSquadWriteAccess já teve o bypass de liderança (isLeadershipJobTitle)
            // rodando ANTES da checagem de squad, então bastava declarar jobTitle="tech lead" no próprio
            // perfil pra escrever em work_items de qualquer squad. O bypass só é seguro quando gated por
            // "matches" (mesma regra de SquadController.requireSquadWriteAccess) — este teste trava isso.
            User caller = User.builder().id("u4").email("lider.de.outra@empresa.com.br")
                    .squadId("SQ-OUTRA").jobTitle("tech lead").build();
            when(userRepository.findById("u4")).thenReturn(Optional.of(caller));
            lenient().when(projectMemberRoleRepository.findByEmailIgnoreCase("lider.de.outra@empresa.com.br")).thenReturn(List.of());

            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.estimateWorkItem(
                    "SQ1", "DDW-1", new WorkItemController.EstimateRequest(8.0), requestAs("u4", "MEMBER")));

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
            verifyNoInteractions(workItemService);
        }

        @Test
        @DisplayName("Deve auto-vincular usuário sem squad à squad do work item e permitir a escrita")
        void shouldAutoLinkUserWithoutSquadAndAllow() {
            // requireSquadWriteAccess (passo 3, "Auto-vinculação caso não possua squad") vincula
            // implicitamente quem ainda não tem squadId/defaultProjectId à squad que está tentando
            // escrever, em vez de rejeitar — mesma regra replicada em SquadController. Não é a
            // ausência de checagem; é a checagem decidindo permitir e reivindicar a squad.
            User caller = User.builder().id("u3").email("sem.squad@empresa.com.br").build();
            when(userRepository.findById("u3")).thenReturn(Optional.of(caller));
            lenient().when(projectMemberRoleRepository.findByEmailIgnoreCase("sem.squad@empresa.com.br")).thenReturn(List.of());

            ResponseEntity<Void> response = controller.estimateWorkItem(
                    "SQ1", "DDW-1", new WorkItemController.EstimateRequest(8.0), requestAs("u3", "MEMBER"));

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals("SQ1", caller.getSquadId());
            verify(userRepository).save(caller);
            verify(workItemService).estimateWorkItem("SQ1", "DDW-1", 8.0);
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

    @Nested
    @DisplayName("Segurança e Controle de Acesso de Leitura por Squad (requireSquadReadAccess)")
    class ReadAuthorizationTests {

        // Regressão: getAssignedWorkItems, getSprintStats, getWorkItemsBySquad e
        // getBacklogEstimated eram GETs que só exigiam autenticação, sem checar pertencimento
        // à squad — qualquer usuário autenticado lia work items de qualquer squad. Mesmo padrão
        // de correção de SquadController.requireSquadReadAccess.

        @Test
        @DisplayName("Deve rejeitar com HTTP 403 leitura de itens atribuídos por membro de outra squad")
        void shouldRejectGetAssignedWorkItemsFromAnotherSquadMember() {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.getAssignedWorkItems(
                    "SQ1", "acc-1", memberOfAnotherSquad()));

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
            verifyNoInteractions(workItemService);
        }

        @Test
        @DisplayName("Deve permitir ao membro da squad ler itens atribuídos")
        void shouldAllowGetAssignedWorkItemsForSquadMember() {
            when(workItemService.getAssignedWorkItems("SQ1", "acc-1")).thenReturn(List.of());

            ResponseEntity<List<WorkItem>> response = controller.getAssignedWorkItems("SQ1", "acc-1", memberOfSq1());

            assertEquals(HttpStatus.OK, response.getStatusCode());
        }

        @Test
        @DisplayName("Deve permitir ao ADMIN ler itens atribuídos de qualquer squad")
        void shouldAllowAdminToReadAssignedWorkItemsOfAnySquad() {
            when(workItemService.getAssignedWorkItems("SQ-QUALQUER", "acc-1")).thenReturn(List.of());

            ResponseEntity<List<WorkItem>> response = controller.getAssignedWorkItems(
                    "SQ-QUALQUER", "acc-1", requestAs("admin1", "ADMIN"));

            assertEquals(HttpStatus.OK, response.getStatusCode());
        }

        @Test
        @DisplayName("Deve rejeitar com HTTP 403 leitura de estatísticas de sprint por membro de outra squad")
        void shouldRejectGetSprintStatsFromAnotherSquadMember() {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.getSprintStats(
                    "SQ1", "SPRINT-42", memberOfAnotherSquad()));

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
            verifyNoInteractions(workItemService);
        }

        @Test
        @DisplayName("Deve permitir ao membro da squad ler estatísticas de sprint")
        void shouldAllowGetSprintStatsForSquadMember() {
            when(workItemService.getSprintStats("SQ1", "SPRINT-42")).thenReturn(Map.of());

            ResponseEntity<Map<String, Object>> response = controller.getSprintStats("SQ1", "SPRINT-42", memberOfSq1());

            assertEquals(HttpStatus.OK, response.getStatusCode());
        }

        @Test
        @DisplayName("Deve rejeitar com HTTP 403 leitura de work items da squad por membro de outra squad")
        void shouldRejectGetWorkItemsBySquadFromAnotherSquadMember() {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.getWorkItemsBySquad(
                    "SQ1", memberOfAnotherSquad()));

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
            verifyNoInteractions(workItemService);
        }

        @Test
        @DisplayName("Deve permitir ao membro da squad listar work items da própria squad")
        void shouldAllowGetWorkItemsBySquadForSquadMember() {
            when(workItemService.getWorkItemsBySquadId("SQ1")).thenReturn(List.of());

            ResponseEntity<List<WorkItem>> response = controller.getWorkItemsBySquad("SQ1", memberOfSq1());

            assertEquals(HttpStatus.OK, response.getStatusCode());
        }

        @Test
        @DisplayName("Deve rejeitar com HTTP 403 leitura de backlog estimado por membro de outra squad")
        void shouldRejectGetBacklogEstimatedFromAnotherSquadMember() {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.getBacklogEstimated(
                    "SQ1", memberOfAnotherSquad()));

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
            verifyNoInteractions(workItemService);
        }

        @Test
        @DisplayName("Deve permitir ao LEAD ler backlog estimado de qualquer squad")
        void shouldAllowLeadToReadBacklogEstimatedOfAnySquad() {
            when(workItemService.getBacklogEstimated("SQ-QUALQUER")).thenReturn(List.of());

            ResponseEntity<List<WorkItem>> response = controller.getBacklogEstimated(
                    "SQ-QUALQUER", requestAs("lead1", "LEAD"));

            assertEquals(HttpStatus.OK, response.getStatusCode());
        }
    }
}
