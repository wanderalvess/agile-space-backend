package com.agilespace.backend.service;

import com.agilespace.backend.domain.SquadIssueSnapshot;
import com.agilespace.backend.domain.SubtaskClass;
import com.agilespace.backend.repository.JiraIssueTypeCacheRepository;
import com.agilespace.backend.repository.SquadIssueSnapshotRepository;
import com.agilespace.backend.repository.SquadSubtaskTypeMappingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlannerBacklogService - backlog classificado, soma por classe, divergência, sobra e tipos sem classe")
class PlannerBacklogServiceTest {

    @Mock private SquadIssueSnapshotRepository snapshotRepository;
    @Mock private SquadSubtaskTypeMappingRepository mappingRepository;
    @Mock private JiraIssueTypeCacheRepository cacheRepository;
    private PlannerBacklogService service;
    private final List<SquadIssueSnapshot> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        SubtaskTypeMappingService mapping = new SubtaskTypeMappingService(mappingRepository, cacheRepository);
        PlannerBacklogSyncService sync = new PlannerBacklogSyncService(null, null, null, null, null, new ObjectMapper(),
                "Refinamento Concluído", "", 300);
        service = new PlannerBacklogService(snapshotRepository, mapping, sync);
        lenient().when(mappingRepository.findBySquadIdAndSprintKey(any(), any())).thenReturn(List.of());
        lenient().when(mappingRepository.findBySquadIdOrderByUpdatedAtDesc(any())).thenReturn(List.of());
        lenient().when(cacheRepository.findBySquadIdOrderByNameAsc(any())).thenReturn(List.of());
        when(snapshotRepository.findBySquadIdAndInPlannerBacklogTrue("SQ")).thenReturn(rows);
    }

    private void parent(String key, String title, String status) {
        rows.add(SquadIssueSnapshot.builder().jiraKey(key).title(title).status(status).inPlannerBacklog(true)
                .plannerSyncedAt("2026-10-20T10:00:00Z").build());
    }

    private void sub(String key, String parent, String typeId, String typeName, String title, Long estH, Long remH) {
        rows.add(SquadIssueSnapshot.builder().jiraKey(key).parentKey(parent).title(title).issueTypeId(typeId).type(typeName)
                .ownOriginalEstimateSec(estH == null ? null : estH * 3600).ownRemainingSec(remH == null ? null : remH * 3600)
                .inPlannerBacklog(true).plannerSyncedAt("2026-10-20T10:00:00Z").build());
    }

    private static void eq(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "esperado " + expected + " e veio " + actual);
    }

    @Test
    @DisplayName("título bate com as subtarefas: soma por classe, sem divergência; Code Review sem estimativa conta 1 h")
    void sumsWithoutDivergence() {
        parent("P-1", "Login (DEV 9h / QA 4h)", "Refinamento Concluído");
        sub("P-2", "P-1", "10036", "Codificação", "Codificar", 8L, 8L);
        sub("P-3", "P-1", "12300", "Code Review", "Code Review", null, null);
        sub("P-4", "P-1", "10013", "Execução de TI", "Executar", 4L, 4L);

        PlannerBacklogService.BacklogView v = service.view("SQ", null, null, false);
        PlannerBacklogService.ParentView p = v.items().get(0);
        assertFalse(p.carryOver());
        eq("9", p.titleHours().dev());
        eq("4", p.titleHours().qa());
        eq("9", p.sumByClass().get(SubtaskClass.DEV));
        eq("4", p.sumByClass().get(SubtaskClass.QA));
        assertFalse(p.divergent());
        assertTrue(p.subtasks().stream().filter(s -> s.key().equals("P-3")).findFirst().orElseThrow().defaultHoursApplied());
        assertEquals("2026-10-20T10:00:00Z", v.syncedAt());
    }

    @Test
    @DisplayName("título diferente das subtarefas: divergência por classe; Code Review QA conta como QA")
    void divergenceAndCodeReviewQa() {
        parent("P-1", "Login (DEV 10h / QA 4h)", "Refinamento Concluído");
        sub("P-2", "P-1", "10036", "Codificação", "Codificar", 8L, 8L);
        sub("P-3", "P-1", "12300", "Code Review", "Code Review QA", 2L, 2L);
        sub("P-4", "P-1", "10013", "Execução de TI", "Executar", 2L, 2L);

        PlannerBacklogService.ParentView p = service.view("SQ", null, null, false).items().get(0);
        eq("8", p.sumByClass().get(SubtaskClass.DEV));
        eq("4", p.sumByClass().get(SubtaskClass.QA));
        assertTrue(p.divergenceDev());
        assertFalse(p.divergenceQa());
        assertTrue(p.divergent());
    }

    @Test
    @DisplayName("sobra da sprint (status fora de Refinamento Concluído) conta o restante e não acusa divergência")
    void carryOverUsesRemaining() {
        parent("P-1", "Login (DEV 10h / QA 4h)", "Em andamento");
        sub("P-2", "P-1", "10036", "Codificação", "Codificar", 10L, 3L);
        sub("P-4", "P-1", "10013", "Execução de TI", "Executar", 4L, 4L);

        PlannerBacklogService.ParentView p = service.view("SQ", null, null, false).items().get(0);
        assertTrue(p.carryOver());
        eq("3", p.sumByClass().get(SubtaskClass.DEV));
        assertFalse(p.divergent());
    }

    @Test
    @DisplayName("tipos sem classificação: lista com quantidade e horas; pai sem horas no título não diverge")
    void unclassifiedTypes() {
        parent("P-1", "Sem horas", "Refinamento Concluído");
        sub("P-2", "P-1", "10999", "Reunião", "Alinhar", 2L, 2L);
        sub("P-3", "P-1", "10999", "Reunião", "Alinhar 2", 1L, 1L);
        parent("P-10", "Outro (DEV 1h / QA 0h)", "Refinamento Concluído");
        sub("P-11", "P-10", "10036", "Codificação", "c", 1L, 1L);

        PlannerBacklogService.BacklogView v = service.view("SQ", null, null, false);
        assertEquals(List.of("P-1", "P-10"), v.items().stream().map(PlannerBacklogService.ParentView::key).toList());
        assertNull(v.items().get(0).titleHours());
        assertFalse(v.items().get(0).divergent());
        assertEquals(1, v.unclassifiedTypes().size());
        assertEquals(2, v.unclassifiedTypes().get(0).count());
        eq("3", v.unclassifiedTypes().get(0).hours());

        assertEquals(List.of("P-10"), service.view("SQ", null, null, true).items().stream().map(PlannerBacklogService.ParentView::key).toList());
        assertEquals(List.of("P-10"), service.view("SQ", null, "outro", false).items().stream().map(PlannerBacklogService.ParentView::key).toList());
    }
}
