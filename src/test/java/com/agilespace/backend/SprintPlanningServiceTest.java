package com.agilespace.backend;

import com.agilespace.backend.domain.SprintPlanning;
import com.agilespace.backend.repository.SprintPlanningRepository;
import com.agilespace.backend.service.SprintPlanningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class SprintPlanningServiceTest {

    @Mock
    private SprintPlanningRepository repository;

    @InjectMocks
    private SprintPlanningService service;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testGetPlanningById() {
        SprintPlanning planning = SprintPlanning.builder().id("plan-123").title("Sprint 12").build();
        when(repository.findById("plan-123")).thenReturn(Optional.of(planning));

        Optional<SprintPlanning> result = service.getPlanner("plan-123");

        assertTrue(result.isPresent());
        assertEquals("Sprint 12", result.get().getTitle());
    }

    @Test
    public void testSavePlanning() {
        SprintPlanning planning = SprintPlanning.builder().title("Sprint 13").build();
        when(repository.save(planning)).thenReturn(planning);

        SprintPlanning saved = service.saveOrUpdatePlanner(planning);

        assertEquals("Sprint 13", saved.getTitle());
        verify(repository, times(1)).save(planning);
    }

    @Test
    public void testDeletePlanner() {
        service.deletePlanner("plan-123");
        verify(repository, times(1)).deleteById("plan-123");
    }
}
