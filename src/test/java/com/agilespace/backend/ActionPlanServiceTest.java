package com.agilespace.backend;

import com.agilespace.backend.domain.ActionPlan;
import com.agilespace.backend.domain.ActionPlanTask;
import com.agilespace.backend.repository.ActionPlanRepository;
import com.agilespace.backend.repository.ActionPlanTaskRepository;
import com.agilespace.backend.service.ActionPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ActionPlanServiceTest {

    @Mock
    private ActionPlanRepository boardRepository;

    @Mock
    private ActionPlanTaskRepository taskRepository;

    @InjectMocks
    private ActionPlanService service;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testCreateBoard() {
        ActionPlan board = new ActionPlan();
        when(boardRepository.save(any(ActionPlan.class))).thenAnswer(i -> i.getArgument(0));

        ActionPlan result = service.createBoard(board);

        assertTrue(result.getIsPublic());
        verify(boardRepository).save(board);
    }

    @Test
    public void testGetBoardById() {
        UUID boardId = UUID.randomUUID();
        ActionPlan board = ActionPlan.builder().id(boardId).title("Plano").build();
        when(boardRepository.findById(boardId)).thenReturn(Optional.of(board));

        ActionPlan result = service.getBoardById(boardId);

        assertEquals("Plano", result.getTitle());
    }

    @Test
    public void testGetBoardByIdNotFound() {
        UUID boardId = UUID.randomUUID();
        when(boardRepository.findById(boardId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.getBoardById(boardId));
    }

    @Test
    public void testListTasks() {
        UUID boardId = UUID.randomUUID();
        when(taskRepository.findByBoardIdOrderByOrderAsc(boardId))
            .thenReturn(Arrays.asList(new ActionPlanTask(), new ActionPlanTask()));

        List<ActionPlanTask> result = service.listTasks(boardId);
        assertEquals(2, result.size());
    }

    @Test
    public void testCreateTaskGeneratesIdAndOrder() {
        UUID boardId = UUID.randomUUID();
        ActionPlanTask task = ActionPlanTask.builder().what("Test").build();
        when(taskRepository.findByBoardIdOrderByOrderAsc(boardId)).thenReturn(new ArrayList<>());
        when(taskRepository.save(any(ActionPlanTask.class))).thenAnswer(i -> i.getArgument(0));

        ActionPlanTask saved = service.createTask(boardId, task);

        assertEquals(boardId, saved.getBoardId());
        assertEquals(0, saved.getOrder());
        verify(taskRepository).save(task);
    }

    @Test
    public void testUpdateTask() {
        UUID taskId = UUID.randomUUID();
        ActionPlanTask existing = ActionPlanTask.builder().id(taskId).what("Old").build();
        ActionPlanTask updated = ActionPlanTask.builder().what("New").status("DONE").order(5).build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(existing));
        when(taskRepository.save(any(ActionPlanTask.class))).thenAnswer(i -> i.getArgument(0));

        ActionPlanTask result = service.updateTask(taskId, updated);

        assertEquals("New", result.getWhat());
        assertEquals("DONE", result.getStatus());
        assertEquals(5, result.getOrder());
    }

    @Test
    public void testUpdateTaskNotFound() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.updateTask(taskId, new ActionPlanTask()));
    }

    @Test
    public void testDeleteTask() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.existsById(taskId)).thenReturn(true);

        service.deleteTask(taskId);

        verify(taskRepository).deleteById(taskId);
    }

    @Test
    public void testDeleteTaskNotFound() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.existsById(taskId)).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> service.deleteTask(taskId));
    }

    @Test
    public void testAddParticipant() {
        UUID boardId = UUID.randomUUID();
        ActionPlan board = ActionPlan.builder().id(boardId).participantIds(new HashSet<>()).build();
        when(boardRepository.findById(boardId)).thenReturn(Optional.of(board));
        when(boardRepository.save(any(ActionPlan.class))).thenAnswer(i -> i.getArgument(0));

        ActionPlan result = service.addParticipant(boardId, "user-123");

        assertTrue(result.getParticipantIds().contains("user-123"));
    }
}
