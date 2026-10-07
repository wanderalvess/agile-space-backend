package com.agilespace.backend.service;

import com.agilespace.backend.domain.ActionPlan;
import com.agilespace.backend.domain.ActionPlanTask;
import com.agilespace.backend.repository.ActionPlanRepository;
import com.agilespace.backend.repository.ActionPlanTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActionPlanService - Gestão do Plano de Ação (5W2H) e Tarefas")
class ActionPlanServiceTest {

    @Mock
    private ActionPlanRepository boardRepository;

    @Mock
    private ActionPlanTaskRepository taskRepository;

    @InjectMocks
    private ActionPlanService service;

    @Nested
    @DisplayName("Gestão do Quadro de Plano de Ação")
    class BoardTests {

        @Test
        @DisplayName("Deve criar quadro definindo visibilidade pública por padrão")
        void shouldCreateBoardWithDefaultPublicVisibility() {
            ActionPlan board = new ActionPlan();
            when(boardRepository.save(any(ActionPlan.class))).thenAnswer(i -> i.getArgument(0));

            ActionPlan result = service.createBoard(board);

            assertTrue(result.getIsPublic());
            verify(boardRepository).save(board);
        }

        @Test
        @DisplayName("Deve recuperar quadro existente por UUID")
        void shouldGetBoardById() {
            UUID boardId = UUID.randomUUID();
            ActionPlan board = ActionPlan.builder().id(boardId).title("Plano de Ação Q3").build();
            when(boardRepository.findById(boardId)).thenReturn(Optional.of(board));

            ActionPlan result = service.getBoardById(boardId);

            assertNotNull(result);
            assertEquals("Plano de Ação Q3", result.getTitle());
        }

        @Test
        @DisplayName("Deve lançar IllegalArgumentException quando quadro não for encontrado")
        void shouldThrowWhenBoardNotFound() {
            UUID boardId = UUID.randomUUID();
            when(boardRepository.findById(boardId)).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class, () -> service.getBoardById(boardId));
        }

        @Test
        @DisplayName("Deve adicionar participante ao conjunto de participantes do quadro")
        void shouldAddParticipantToBoard() {
            UUID boardId = UUID.randomUUID();
            ActionPlan board = ActionPlan.builder().id(boardId).participantIds(new HashSet<>()).build();
            when(boardRepository.findById(boardId)).thenReturn(Optional.of(board));
            when(boardRepository.save(any(ActionPlan.class))).thenAnswer(i -> i.getArgument(0));

            ActionPlan result = service.addParticipant(boardId, "user-123");

            assertTrue(result.getParticipantIds().contains("user-123"));
        }
    }

    @Nested
    @DisplayName("Gestão de Tarefas do Plano de Ação")
    class TaskTests {

        @Test
        @DisplayName("Deve listar tarefas ordenadas por ordem crescente")
        void shouldListTasksOrdered() {
            UUID boardId = UUID.randomUUID();
            when(taskRepository.findByBoardIdOrderByOrderAsc(boardId))
                    .thenReturn(Arrays.asList(new ActionPlanTask(), new ActionPlanTask()));

            List<ActionPlanTask> result = service.listTasks(boardId);

            assertEquals(2, result.size());
            verify(taskRepository).findByBoardIdOrderByOrderAsc(boardId);
        }

        @Test
        @DisplayName("Deve criar tarefa associando boardId e calculando a ordem na fila")
        void shouldCreateTaskAndComputeOrder() {
            UUID boardId = UUID.randomUUID();
            ActionPlanTask task = ActionPlanTask.builder().what("Definir métricas DORA").build();
            when(taskRepository.findByBoardIdOrderByOrderAsc(boardId)).thenReturn(new ArrayList<>());
            when(taskRepository.save(any(ActionPlanTask.class))).thenAnswer(i -> i.getArgument(0));

            ActionPlanTask saved = service.createTask(boardId, task);

            assertEquals(boardId, saved.getBoardId());
            assertEquals(0, saved.getOrder());
            verify(taskRepository).save(task);
        }

        @Test
        @DisplayName("Deve atualizar campos da tarefa mantendo integridade")
        void shouldUpdateTaskSuccessfully() {
            UUID taskId = UUID.randomUUID();
            ActionPlanTask existing = ActionPlanTask.builder().id(taskId).what("Antigo").build();
            ActionPlanTask updatePayload = ActionPlanTask.builder().what("Novo").status("DONE").order(5).build();

            when(taskRepository.findById(taskId)).thenReturn(Optional.of(existing));
            when(taskRepository.save(any(ActionPlanTask.class))).thenAnswer(i -> i.getArgument(0));

            ActionPlanTask result = service.updateTask(taskId, updatePayload);

            assertEquals("Novo", result.getWhat());
            assertEquals("DONE", result.getStatus());
            assertEquals(5, result.getOrder());
        }

        @Test
        @DisplayName("Deve excluir tarefa quando ela existir")
        void shouldDeleteTaskWhenExists() {
            UUID taskId = UUID.randomUUID();
            when(taskRepository.existsById(taskId)).thenReturn(true);

            service.deleteTask(taskId);

            verify(taskRepository).deleteById(taskId);
        }

        @Test
        @DisplayName("Deve lançar IllegalArgumentException ao tentar excluir tarefa inexistente")
        void shouldThrowWhenDeletingNonExistentTask() {
            UUID taskId = UUID.randomUUID();
            when(taskRepository.existsById(taskId)).thenReturn(false);

            assertThrows(IllegalArgumentException.class, () -> service.deleteTask(taskId));
        }
    }
}
