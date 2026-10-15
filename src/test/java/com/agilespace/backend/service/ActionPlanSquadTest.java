package com.agilespace.backend.service;

import com.agilespace.backend.domain.ActionPlan;
import com.agilespace.backend.domain.ActionPlanTask;
import com.agilespace.backend.repository.ActionPlanRepository;
import com.agilespace.backend.repository.ActionPlanTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActionPlanService - squad do plano e notificação do responsável")
class ActionPlanSquadTest {

    @Mock private ActionPlanRepository boardRepository;
    @Mock private ActionPlanTaskRepository taskRepository;
    @Mock private SquadAudienceService audienceService;
    @Mock private NotificationTriggers triggers;
    private ActionPlanService service;

    @BeforeEach
    void setup() {
        service = new ActionPlanService(boardRepository, taskRepository);
        service.setAudienceService(audienceService);
        service.setNotificationTriggers(triggers);
    }

    @Test
    @DisplayName("Criar: team que é o id de uma squad cadastrada vira squadId; texto livre não")
    void createDerivesSquadId() {
        when(boardRepository.save(any(ActionPlan.class))).thenAnswer(i -> i.getArgument(0));
        when(audienceService.squadNames(List.of("DDWMISSI"))).thenReturn(Map.of("DDWMISSI", "Missões"));
        when(audienceService.squadNames(List.of("Squad Geral"))).thenReturn(Map.of());

        ActionPlan known = service.createBoard(ActionPlan.builder().title("Plano").team("DDWMISSI").creatorId("u1").build());
        ActionPlan free = service.createBoard(ActionPlan.builder().title("Plano").team("Squad Geral").creatorId("u1").build());

        assertEquals("DDWMISSI", known.getSquadId());
        assertNull(free.getSquadId());
    }

    @Test
    @DisplayName("Listagem por squad usa o repositório com limite")
    void listBySquad() {
        service.listBoardsBySquad("SQA");
        verify(boardRepository).findBySquad(eq("SQA"), any());
    }

    @Test
    @DisplayName("Tarefa com responsável novo notifica; mesmo responsável não repete")
    void assigneeTrigger() {
        UUID boardId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        ActionPlan plan = ActionPlan.builder().id(boardId).title("Plano").squadId("SQA").build();
        when(boardRepository.findById(boardId)).thenReturn(Optional.of(plan));
        when(taskRepository.save(any(ActionPlanTask.class))).thenAnswer(i -> i.getArgument(0));
        ActionPlanTask existing = ActionPlanTask.builder().id(taskId).boardId(boardId).what("Ajustar CI").who("Ana").build();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(existing));

        service.updateTask(taskId, ActionPlanTask.builder().who("Ana").build(), "u1");
        verifyNoInteractions(triggers);

        service.updateTask(taskId, ActionPlanTask.builder().who("Bob").build(), "u1");
        verify(triggers).actionPlanTaskAssigned(eq(boardId.toString()), eq("Plano"), eq("SQA"), eq(taskId.toString()),
                eq("Ajustar CI"), eq("Bob"), eq("u1"));
    }
}
