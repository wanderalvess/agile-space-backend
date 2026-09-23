package com.agilespace.backend.service;

import com.agilespace.backend.domain.SprintPlanning;
import com.agilespace.backend.domain.SprintPlanningMember;
import com.agilespace.backend.domain.SprintPlanningSubtask;
import com.agilespace.backend.domain.SprintPlanningTask;
import com.agilespace.backend.repository.SprintPlanningMemberRepository;
import com.agilespace.backend.repository.SprintPlanningRepository;
import com.agilespace.backend.repository.SprintPlanningSubtaskRepository;
import com.agilespace.backend.repository.SprintPlanningTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SprintPlanningService - Planejamento de Sprints, Metas e Capacidade")
class SprintPlanningServiceTest {

    @Mock
    private SprintPlanningRepository repository;

    @Mock
    private SprintPlanningTaskRepository taskRepository;

    @Mock
    private SprintPlanningSubtaskRepository subtaskRepository;

    @Mock
    private SprintPlanningMemberRepository memberRepository;

    @InjectMocks
    private SprintPlanningService service;

    private SprintPlanning samplePlan;

    @BeforeEach
    void setUp() {
        samplePlan = SprintPlanning.builder()
                .id("plan-123")
                .title("Planejamento Sprint 45")
                .build();
    }

    @Nested
    @DisplayName("Operações de Planejamento")
    class PlanningOperationsTests {

        @Test
        @DisplayName("Deve recuperar planejamento por ID com tasks (e subtasks) e members montados")
        void shouldGetPlanningByIdWithChildrenAttached() {
            SprintPlanningTask task = SprintPlanningTask.builder().id("t1").planningId("plan-123").name("Task 1").order(0).build();
            SprintPlanningSubtask subtask = SprintPlanningSubtask.builder().id("s1").taskId("t1").name("Sub 1").order(0).build();
            SprintPlanningMember member = SprintPlanningMember.builder().id("m1").planningId("plan-123").name("Dev 1").order(0).build();

            when(repository.findById("plan-123")).thenReturn(Optional.of(samplePlan));
            when(taskRepository.findByPlanningIdOrderByOrderAsc("plan-123")).thenReturn(List.of(task));
            when(subtaskRepository.findByTaskIdInOrderByOrderAsc(List.of("t1"))).thenReturn(List.of(subtask));
            when(memberRepository.findByPlanningIdOrderByOrderAsc("plan-123")).thenReturn(List.of(member));

            Optional<SprintPlanning> result = service.getPlanner("plan-123");

            assertTrue(result.isPresent());
            assertEquals(1, result.get().getTasks().size());
            assertEquals(1, result.get().getTasks().get(0).getSubtasks().size());
            assertEquals("Sub 1", result.get().getTasks().get(0).getSubtasks().get(0).getName());
            assertEquals(1, result.get().getMembers().size());
        }

        @Test
        @DisplayName("Deve retornar vazio quando planejamento não existe")
        void shouldReturnEmptyWhenPlannerNotFound() {
            when(repository.findById("inexistente")).thenReturn(Optional.empty());

            Optional<SprintPlanning> result = service.getPlanner("inexistente");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Deve salvar planejamento associando autor e substituindo tasks/members existentes")
        void shouldSavePlanningReplacingChildren() {
            SprintPlanningTask task = SprintPlanningTask.builder().name("Nova Task").build();
            SprintPlanningMember member = SprintPlanningMember.builder().name("Dev 1").build();
            samplePlan.setTasks(new ArrayList<>(List.of(task)));
            samplePlan.setMembers(new ArrayList<>(List.of(member)));

            when(repository.save(samplePlan)).thenReturn(samplePlan);
            when(taskRepository.findByPlanningIdOrderByOrderAsc("plan-123")).thenReturn(List.of());
            when(memberRepository.findByPlanningIdOrderByOrderAsc("plan-123")).thenReturn(List.of(member));

            SprintPlanning saved = service.saveOrUpdatePlanner(samplePlan, "user-sm");

            assertEquals("Planejamento Sprint 45", saved.getTitle());
            verify(repository, times(1)).save(samplePlan);
            // Substituição = apaga o que já existia pra essa planning antes de regravar
            verify(taskRepository).deleteByPlanningId("plan-123");
            verify(memberRepository).deleteByPlanningId("plan-123");
            verify(taskRepository).save(argThat(t -> "plan-123".equals(t.getPlanningId()) && t.getOrder() == 0));
            verify(memberRepository).saveAll(argThat((List<SprintPlanningMember> ms) ->
                    ms.size() == 1 && "plan-123".equals(ms.get(0).getPlanningId()) && ms.get(0).getOrder() == 0));
        }

        @Test
        @DisplayName("Deve excluir planejamento e suas tasks, subtasks e members")
        void shouldDeletePlannerCascadingChildren() {
            SprintPlanningTask task = SprintPlanningTask.builder().id("t1").planningId("plan-123").build();
            when(taskRepository.findByPlanningIdOrderByOrderAsc("plan-123")).thenReturn(List.of(task));

            service.deletePlanner("plan-123");

            verify(subtaskRepository).deleteByTaskIdIn(List.of("t1"));
            verify(taskRepository).deleteByPlanningId("plan-123");
            verify(memberRepository).deleteByPlanningId("plan-123");
            verify(repository, times(1)).deleteById("plan-123");
        }

        @Test
        @DisplayName("Deve listar planejamentos prontos pro poker respeitando o limite")
        void shouldListReadyForPoker() {
            when(repository.findReadyForPoker(PageRequest.of(0, 5))).thenReturn(List.of(samplePlan));
            when(taskRepository.findByPlanningIdOrderByOrderAsc("plan-123")).thenReturn(List.of());
            when(memberRepository.findByPlanningIdOrderByOrderAsc("plan-123")).thenReturn(List.of());

            List<SprintPlanning> result = service.listReadyForPoker(5);

            assertEquals(1, result.size());
        }
    }

    @Nested
    @DisplayName("Validação de Entrada")
    class ValidationTests {

        @Test
        @DisplayName("Deve rejeitar planejamento sem título")
        void shouldRejectBlankTitle() {
            SprintPlanning blank = SprintPlanning.builder().title("   ").build();

            assertThrows(ResponseStatusException.class, () -> service.saveOrUpdatePlanner(blank, "user-sm"));
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("Deve rejeitar quando a lista de tasks excede o limite")
        void shouldRejectTasksOverLimit() {
            List<SprintPlanningTask> tooMany = new ArrayList<>();
            for (int i = 0; i < 2001; i++) {
                tooMany.add(SprintPlanningTask.builder().name("Task " + i).build());
            }
            SprintPlanning planning = SprintPlanning.builder().title("Sprint 14").tasks(tooMany).build();

            assertThrows(ResponseStatusException.class, () -> service.saveOrUpdatePlanner(planning, "user-sm"));
            verify(repository, never()).save(any());
        }
    }
}
