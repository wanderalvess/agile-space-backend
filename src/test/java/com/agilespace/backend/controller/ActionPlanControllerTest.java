package com.agilespace.backend.controller;

import com.agilespace.backend.domain.ActionPlan;
import com.agilespace.backend.domain.ActionPlanTask;
import com.agilespace.backend.service.ActionPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class ActionPlanControllerTest {

    @Mock
    private ActionPlanService service;

    @InjectMocks
    private ActionPlanController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testCreateBoard() {
        ActionPlan board = new ActionPlan();
        when(service.createBoard(board)).thenReturn(board);
        
        ResponseEntity<ActionPlan> response = controller.createBoard(board);
        
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        verify(service, times(1)).createBoard(board);
    }

    @Test
    public void testGetBoardByIdSuccess() {
        UUID id = UUID.randomUUID();
        ActionPlan board = new ActionPlan();
        when(service.getBoardById(id)).thenReturn(board);
        
        ResponseEntity<ActionPlan> response = controller.getBoardById(id);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testGetBoardByIdNotFound() {
        UUID id = UUID.randomUUID();
        when(service.getBoardById(id)).thenThrow(new IllegalArgumentException());
        
        ResponseEntity<ActionPlan> response = controller.getBoardById(id);
        
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    public void testListTasks() {
        UUID id = UUID.randomUUID();
        when(service.listTasks(id)).thenReturn(Arrays.asList(new ActionPlanTask()));
        
        ResponseEntity<List<ActionPlanTask>> response = controller.listTasks(id);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
    }
}
