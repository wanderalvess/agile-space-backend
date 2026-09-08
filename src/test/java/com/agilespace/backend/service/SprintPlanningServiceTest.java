package com.agilespace.backend.service;

import com.agilespace.backend.domain.SprintPlanning;
import com.agilespace.backend.repository.SprintPlanningRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SprintPlanningService - Planejamento de Sprints, Metas e Capacidade")
class SprintPlanningServiceTest {

    @Mock
    private SprintPlanningRepository repository;

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
        @DisplayName("Deve recuperar planejamento por ID com sucesso")
        void shouldGetPlanningById() {
            when(repository.findById("plan-123")).thenReturn(Optional.of(samplePlan));

            Optional<SprintPlanning> result = service.getPlanner("plan-123");

            assertTrue(result.isPresent());
            assertEquals("Planejamento Sprint 45", result.get().getTitle());
        }

        @Test
        @DisplayName("Deve salvar ou atualizar planejamento associando autor")
        void shouldSavePlanning() {
            when(repository.save(samplePlan)).thenReturn(samplePlan);

            SprintPlanning saved = service.saveOrUpdatePlanner(samplePlan, "user-sm");

            assertEquals("Planejamento Sprint 45", saved.getTitle());
            verify(repository, times(1)).save(samplePlan);
        }

        @Test
        @DisplayName("Deve excluir planejamento pelo identificador")
        void shouldDeletePlanner() {
            service.deletePlanner("plan-123");

            verify(repository, times(1)).deleteById("plan-123");
        }
    }
}
