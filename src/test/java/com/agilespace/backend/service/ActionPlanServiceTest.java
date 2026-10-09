package com.agilespace.backend.service;

import com.agilespace.backend.domain.ActionPlan;
import com.agilespace.backend.domain.ActionPlanTask;
import com.agilespace.backend.repository.ActionPlanRepository;
import com.agilespace.backend.repository.ActionPlanTaskRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
@DisplayName("ActionPlanService - Plano de Ação (5W2H): criação segura e edição parcial")
class ActionPlanServiceTest {

    @Mock
    private ActionPlanRepository boardRepository;

    @Mock
    private ActionPlanTaskRepository taskRepository;

    @InjectMocks
    private ActionPlanService service;

    private static void assertBadRequest(Runnable action) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Nested
    @DisplayName("Quadro")
    class BoardTests {

        @Test
        @DisplayName("Cria quadro público por padrão, com o criador entre os participantes")
        void createsPublicBoardWithCreatorParticipant() {
            ActionPlan board = ActionPlan.builder().title("  Plano Q3  ").creatorId("u1").build();
            when(boardRepository.save(any(ActionPlan.class))).thenAnswer(i -> i.getArgument(0));

            ActionPlan result = service.createBoard(board);

            assertTrue(result.getIsPublic());
            assertEquals("Plano Q3", result.getTitle());
            assertTrue(result.getParticipantIds().contains("u1"));
        }

        @Test
        @DisplayName("Ignora o id do corpo (não sobrescreve outro plano) e exige título")
        void ignoresBodyIdAndRequiresTitle() {
            ActionPlan board = ActionPlan.builder().id(UUID.randomUUID()).title("Plano").creatorId("u1").build();
            when(boardRepository.save(any(ActionPlan.class))).thenAnswer(i -> i.getArgument(0));

            assertNull(service.createBoard(board).getId());
            assertBadRequest(() -> service.createBoard(ActionPlan.builder().title("   ").creatorId("u1").build()));
            assertBadRequest(() -> service.createBoard(ActionPlan.builder().creatorId("u1").build()));
        }

        @Test
        @DisplayName("Recupera quadro por UUID e lança IllegalArgumentException quando não existe")
        void getsBoardOrThrows() {
            UUID boardId = UUID.randomUUID();
            ActionPlan board = ActionPlan.builder().id(boardId).title("Plano de Ação Q3").build();
            when(boardRepository.findById(boardId)).thenReturn(Optional.of(board));
            UUID missing = UUID.randomUUID();
            when(boardRepository.findById(missing)).thenReturn(Optional.empty());

            assertEquals("Plano de Ação Q3", service.getBoardById(boardId).getTitle());
            assertThrows(IllegalArgumentException.class, () -> service.getBoardById(missing));
        }

        @Test
        @DisplayName("Adiciona participante ao quadro")
        void addsParticipant() {
            UUID boardId = UUID.randomUUID();
            ActionPlan board = ActionPlan.builder().id(boardId).participantIds(new HashSet<>()).build();
            when(boardRepository.findById(boardId)).thenReturn(Optional.of(board));
            when(boardRepository.save(any(ActionPlan.class))).thenAnswer(i -> i.getArgument(0));

            assertTrue(service.addParticipant(boardId, "user-123").getParticipantIds().contains("user-123"));
        }
    }

    @Nested
    @DisplayName("Tarefas")
    class TaskTests {

        @Test
        @DisplayName("Lista tarefas ordenadas")
        void listsTasks() {
            UUID boardId = UUID.randomUUID();
            when(taskRepository.findByBoardIdOrderByOrderAsc(boardId))
                    .thenReturn(Arrays.asList(new ActionPlanTask(), new ActionPlanTask()));

            assertEquals(2, service.listTasks(boardId).size());
        }

        @Test
        @DisplayName("Cria tarefa no board da URL, autor do login, id/datas do corpo ignorados, ordem no fim da fila")
        void createsTaskWithServerFields() {
            UUID boardId = UUID.randomUUID();
            ActionPlanTask body = ActionPlanTask.builder().id(UUID.randomUUID()).boardId(UUID.randomUUID()).authorId("impostor")
                    .what("  Definir métricas DORA  ").status("DOING").build();
            when(taskRepository.findByBoardIdOrderByOrderAsc(boardId)).thenReturn(new ArrayList<>(List.of(new ActionPlanTask())));
            when(taskRepository.save(any(ActionPlanTask.class))).thenAnswer(i -> i.getArgument(0));

            ActionPlanTask saved = service.createTask(boardId, body, "u1");

            assertNull(saved.getId());
            assertEquals(boardId, saved.getBoardId());
            assertEquals("u1", saved.getAuthorId());
            assertEquals("Definir métricas DORA", saved.getWhat());
            assertEquals("doing", saved.getStatus());
            assertEquals(1, saved.getOrder());
        }

        @Test
        @DisplayName("Tarefa sem 'o quê', com status inválido ou texto gigante é recusada; sem status vira 'todo'")
        void validatesNewTask() {
            UUID boardId = UUID.randomUUID();
            assertBadRequest(() -> service.createTask(boardId, ActionPlanTask.builder().what(" ").build(), "u1"));
            assertBadRequest(() -> service.createTask(boardId, ActionPlanTask.builder().what("x").status("feito").build(), "u1"));
            assertBadRequest(() -> service.createTask(boardId, ActionPlanTask.builder().what("x".repeat(5001)).build(), "u1"));
            assertBadRequest(() -> service.createTask(boardId, ActionPlanTask.builder().what("x").who("y".repeat(256)).build(), "u1"));

            when(taskRepository.findByBoardIdOrderByOrderAsc(boardId)).thenReturn(new ArrayList<>());
            when(taskRepository.save(any(ActionPlanTask.class))).thenAnswer(i -> i.getArgument(0));
            assertEquals("todo", service.createTask(boardId, ActionPlanTask.builder().what("x").build(), "u1").getStatus());
        }

        @Test
        @DisplayName("Editar um único campo NÃO apaga os outros (antes a edição na tabela zerava o resto)")
        void partialUpdateKeepsOtherFields() {
            UUID taskId = UUID.randomUUID();
            ActionPlanTask existing = ActionPlanTask.builder().id(taskId).what("Antigo").why("Porque sim").where("Backend")
                    .when("15/10").who("Ana").how("Com calma").howMuch("8h").status("doing").order(3).build();
            when(taskRepository.findById(taskId)).thenReturn(Optional.of(existing));
            when(taskRepository.save(any(ActionPlanTask.class))).thenAnswer(i -> i.getArgument(0));

            ActionPlanTask result = service.updateTask(taskId, ActionPlanTask.builder().what("Novo").build());

            assertEquals("Novo", result.getWhat());
            assertEquals("Porque sim", result.getWhy());
            assertEquals("Backend", result.getWhere());
            assertEquals("15/10", result.getWhen());
            assertEquals("Ana", result.getWho());
            assertEquals("Com calma", result.getHow());
            assertEquals("8h", result.getHowMuch());
            assertEquals("doing", result.getStatus());
            assertEquals(3, result.getOrder());
        }

        @Test
        @DisplayName("Só o status muda quando só o status é enviado; texto vazio limpa o campo")
        void statusOnlyAndClearing() {
            UUID taskId = UUID.randomUUID();
            ActionPlanTask existing = ActionPlanTask.builder().id(taskId).what("Fazer").who("Ana").status("todo").build();
            when(taskRepository.findById(taskId)).thenReturn(Optional.of(existing));
            when(taskRepository.save(any(ActionPlanTask.class))).thenAnswer(i -> i.getArgument(0));

            ActionPlanTask afterStatus = service.updateTask(taskId, ActionPlanTask.builder().status("DONE").build());
            assertEquals("done", afterStatus.getStatus());
            assertEquals("Fazer", afterStatus.getWhat());
            assertEquals("Ana", afterStatus.getWho());

            ActionPlanTask afterClear = service.updateTask(taskId, ActionPlanTask.builder().who("").build());
            assertEquals("", afterClear.getWho());
        }

        @Test
        @DisplayName("Não deixa esvaziar 'o quê' nem gravar status inválido")
        void updateValidation() {
            UUID taskId = UUID.randomUUID();
            ActionPlanTask existing = ActionPlanTask.builder().id(taskId).what("Fazer").status("todo").build();
            when(taskRepository.findById(taskId)).thenReturn(Optional.of(existing));

            assertBadRequest(() -> service.updateTask(taskId, ActionPlanTask.builder().what("  ").build()));
            assertBadRequest(() -> service.updateTask(taskId, ActionPlanTask.builder().status("zzz").build()));
            verify(taskRepository, never()).save(any());
        }

        @Test
        @DisplayName("Atualizar tarefa inexistente lança IllegalArgumentException")
        void updateMissing() {
            UUID taskId = UUID.randomUUID();
            when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class, () -> service.updateTask(taskId, new ActionPlanTask()));
        }

        @Test
        @DisplayName("Exclui tarefa existente e lança para inexistente")
        void deletes() {
            UUID taskId = UUID.randomUUID();
            when(taskRepository.existsById(taskId)).thenReturn(true);
            service.deleteTask(taskId);
            verify(taskRepository).deleteById(taskId);

            UUID missing = UUID.randomUUID();
            when(taskRepository.existsById(missing)).thenReturn(false);
            assertThrows(IllegalArgumentException.class, () -> service.deleteTask(missing));
        }
    }
}
