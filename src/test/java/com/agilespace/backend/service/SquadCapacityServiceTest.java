package com.agilespace.backend.service;

import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.domain.SquadPersonConfig;
import com.agilespace.backend.repository.SquadPersonConfigRepository;
import com.agilespace.backend.repository.SquadRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

public class SquadCapacityServiceTest {

    @Mock private SquadPersonConfigRepository repository;
    @Mock private SquadRepository squadRepository;

    private SquadCapacityService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        service = new SquadCapacityService(repository, squadRepository);
    }

    private SquadPersonConfig config(String papel, Integer diasCodTeste, Integer diasRegressivo, Double horas) {
        return SquadPersonConfig.builder()
                .papel(papel).diasCodificacaoTeste(diasCodTeste).diasRegressivo(diasRegressivo).horasProdutivas(horas)
                .build();
    }

    private void stubSprintHistory(String squadId, String... sprintIdsOldestFirst) {
        ArrayNode history = objectMapper.createArrayNode();
        int day = 1;
        for (String sprintId : sprintIdsOldestFirst) {
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("sprintId", sprintId);
            entry.put("sprintStart", String.format("2026-01-%02d", day));
            history.add(entry);
            day += 10;
        }
        Squad squad = Squad.builder().id(squadId).sprintHistory(history).build();
        when(squadRepository.findById(squadId)).thenReturn(Optional.of(squad));
    }

    // ---------- validação ----------

    @Test
    public void save_rejectsInvalidPapel() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.save("SQ1", "acc-1", "SPRINT-1", config("TESTER", null, null, null)));
        assertTrue(ex.getReason().contains("Papel inválido"));
    }

    @Test
    public void save_rejectsDiasOutOfRange() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.save("SQ1", "acc-1", "SPRINT-1", config(null, 32, null, null)));
        assertTrue(ex.getReason().contains("Codificação/Teste"));
    }

    @Test
    public void save_rejectsHorasOutOfRange() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.save("SQ1", "acc-1", "SPRINT-1", config(null, null, null, 25.0)));
        assertTrue(ex.getReason().contains("entre 0 e 24"));
    }

    @Test
    public void save_rejectsHorasWithMoreThanTwoDecimals() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.save("SQ1", "acc-1", "SPRINT-1", config(null, null, null, 5.123)));
        assertTrue(ex.getReason().contains("casas decimais"));
    }

    @Test
    public void save_acceptsValidConfigAndDefaultsSprintIdToGlobalWhenBlank() {
        when(repository.findById("SQ1_GLOBAL_acc-1")).thenReturn(Optional.empty());
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));

        SquadPersonConfig saved = service.save("SQ1", "acc-1", null, config("DEV", 5, 2, 6.5));

        assertEquals("SQ1_GLOBAL_acc-1", saved.getDbId());
        assertEquals(SquadPersonConfig.GLOBAL_SPRINT_ID, saved.getSprintId());
        assertEquals("DEV", saved.getPapel());
    }

    // ---------- resolução com herança ----------

    @Test
    public void resolve_prefersCurrentSprintOverEverything() {
        stubSprintHistory("SQ1");
        when(repository.findBySquadIdAndSprintIdAndJiraAccountId("SQ1", "SPRINT-2", "acc-1"))
                .thenReturn(Optional.of(config("QA", 8, 0, 7.0)));

        SquadCapacityService.ResolvedPersonConfig resolved = service.resolve("SQ1", "SPRINT-2", "acc-1");

        assertEquals("QA", resolved.papel());
        assertFalse(resolved.papelInherited());
        assertEquals(8, resolved.diasCodificacaoTeste());
        assertEquals(7.0, resolved.horasProdutivas());
    }

    @Test
    public void resolve_fallsBackToMostRecentPriorSprintWithAValue() {
        // SPRINT-3 (atual) não tem config nenhuma; SPRINT-2 (mais recente) tem; SPRINT-1 (mais antiga) também tem,
        // mas SPRINT-2 deve vencer por ser a mais recente das duas.
        stubSprintHistory("SQ1", "SPRINT-1", "SPRINT-2");
        when(repository.findBySquadIdAndSprintIdAndJiraAccountId("SQ1", "SPRINT-1", "acc-1"))
                .thenReturn(Optional.of(config("DEV", 3, 3, 8.0)));
        when(repository.findBySquadIdAndSprintIdAndJiraAccountId("SQ1", "SPRINT-2", "acc-1"))
                .thenReturn(Optional.of(config("QA", 5, 1, 6.0)));

        SquadCapacityService.ResolvedPersonConfig resolved = service.resolve("SQ1", "SPRINT-3", "acc-1");

        assertEquals("QA", resolved.papel());
        assertTrue(resolved.papelInherited());
        assertEquals("SPRINT-2", resolved.papelSource());
        assertEquals(5, resolved.diasCodificacaoTeste());
    }

    @Test
    public void resolve_fallsBackToGlobalDefaultWhenNoSprintHasAValue() {
        stubSprintHistory("SQ1", "SPRINT-1");
        when(repository.findBySquadIdAndSprintIdAndJiraAccountId("SQ1", SquadPersonConfig.GLOBAL_SPRINT_ID, "acc-1"))
                .thenReturn(Optional.of(config("DEV", 10, 0, 8.0)));

        SquadCapacityService.ResolvedPersonConfig resolved = service.resolve("SQ1", "SPRINT-2", "acc-1");

        assertEquals("DEV", resolved.papel());
        assertTrue(resolved.papelInherited());
        assertEquals("GLOBAL", resolved.papelSource());
    }

    @Test
    public void resolve_fallsBackToHardDefaultsWhenNothingConfiguredAnywhere() {
        stubSprintHistory("SQ1");

        SquadCapacityService.ResolvedPersonConfig resolved = service.resolve("SQ1", "SPRINT-1", "acc-1");

        assertEquals("", resolved.papel());
        assertEquals(0, resolved.diasCodificacaoTeste());
        assertEquals(0, resolved.diasRegressivo());
        assertEquals(8.0, resolved.horasProdutivas());
        assertFalse(resolved.horasProdutivasInherited());
    }

    @Test
    public void resolve_resolvesEachFieldIndependently() {
        // papel fixado nesta sprint; horas ainda vem da sprint anterior — herança por
        // campo, não um "tudo ou nada" por linha (comportamento real do person-config.js).
        stubSprintHistory("SQ1", "SPRINT-1");
        when(repository.findBySquadIdAndSprintIdAndJiraAccountId("SQ1", "SPRINT-2", "acc-1"))
                .thenReturn(Optional.of(config("QA", null, null, null)));
        when(repository.findBySquadIdAndSprintIdAndJiraAccountId("SQ1", "SPRINT-1", "acc-1"))
                .thenReturn(Optional.of(config("DEV", null, null, 6.0)));

        SquadCapacityService.ResolvedPersonConfig resolved = service.resolve("SQ1", "SPRINT-2", "acc-1");

        assertEquals("QA", resolved.papel());
        assertFalse(resolved.papelInherited());
        assertEquals(6.0, resolved.horasProdutivas());
        assertTrue(resolved.horasProdutivasInherited());
        assertEquals("SPRINT-1", resolved.horasProdutivasSource());
    }

    // ---------- cálculo de capacidade ----------

    @Test
    public void capacityHours_neverSumsDaysWithHours() {
        stubSprintHistory("SQ1");
        when(repository.findBySquadIdAndSprintIdAndJiraAccountId("SQ1", "SPRINT-1", "acc-1"))
                .thenReturn(Optional.of(config("DEV", 7, 3, 6.0)));

        SquadCapacityService.ResolvedPersonConfig resolved = service.resolve("SQ1", "SPRINT-1", "acc-1");

        assertEquals(42.0, resolved.capacityCodificacaoTesteHours(), 0.0001);
        assertEquals(18.0, resolved.capacityRegressivoHours(), 0.0001);
        assertEquals(60.0, resolved.capacityHours(), 0.0001);
    }
}
