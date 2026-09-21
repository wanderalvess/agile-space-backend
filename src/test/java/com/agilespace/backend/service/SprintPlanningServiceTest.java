package com.agilespace.backend.service;

import com.agilespace.backend.domain.SprintPlanning;
import com.agilespace.backend.repository.SprintPlanningRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

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
        @DisplayName("Deve rejeitar tasks que não sejam uma lista JSON")
        void shouldRejectTasksThatAreNotAnArray() throws Exception {
            JsonNode notAnArray = new ObjectMapper().readTree("{\"oops\": true}");
            SprintPlanning planning = SprintPlanning.builder().title("Sprint 14").tasks(notAnArray).build();

            assertThrows(ResponseStatusException.class, () -> service.saveOrUpdatePlanner(planning, "user-sm"));
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("Deve rejeitar settings que não sejam um objeto JSON")
        void shouldRejectSettingsThatAreNotAnObject() {
            SprintPlanning planning = SprintPlanning.builder().title("Sprint 14").settings(TextNode.valueOf("not-an-object")).build();

            assertThrows(ResponseStatusException.class, () -> service.saveOrUpdatePlanner(planning, "user-sm"));
            verify(repository, never()).save(any());
        }
    }
}
