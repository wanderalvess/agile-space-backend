package com.agilespace.backend.controller;

import com.agilespace.backend.domain.SprintPlanning;
import com.agilespace.backend.service.SprintPlanningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class SprintPlanningControllerTest {

    @Mock
    private SprintPlanningService service;

    @InjectMocks
    private SprintPlanningController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testGetPlanner() {
        SprintPlanning planning = new SprintPlanning();
        when(service.getPlanner("plan-1")).thenReturn(Optional.of(planning));
        
        ResponseEntity<SprintPlanning> response = controller.getPlanner("plan-1");
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testSaveOrUpdatePlanner() {
        SprintPlanning planning = new SprintPlanning();
        when(service.saveOrUpdatePlanner(planning)).thenReturn(planning);
        
        ResponseEntity<SprintPlanning> response = controller.saveOrUpdatePlanner(planning);
        
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
    }

    @Test
    public void testDeletePlanner() {
        doNothing().when(service).deletePlanner("plan-1");
        
        ResponseEntity<Void> response = controller.deletePlanner("plan-1");
        
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }
}
