package com.agilespace.backend.controller;

import com.agilespace.backend.domain.SubtaskClass;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SprintPlannerBacklogController - permissões (liderança/ADMIN escreve, membro lê) e guarda de sync")
class SprintPlannerBacklogControllerTest {

    @Mock private SquadAccessService access;
    @Mock private SquadSyncGuard guard;
    @Mock private PlannerBacklogSyncService syncService;
    @Mock private PlannerBacklogService backlogService;
    @Mock private SubtaskTypeMappingService mappingService;
    @Mock private HttpServletRequest request;
    private SprintPlannerBacklogController controller;

    @BeforeEach
    void setUp() {
        controller = new SprintPlannerBacklogController(access, guard, syncService, backlogService, mappingService);
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("u1");
    }

    private void member() {
        lenient().when(access.canManageSquad("SQ", request)).thenReturn(false);
    }

    private void leader() {
        lenient().when(access.canManageSquad("SQ", request)).thenReturn(true);
    }

    @Test
    @DisplayName("membro não sincroniza o backlog (403) e o Jira nem é chamado")
    void memberCannotSync() {
        member();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.syncBacklog("SQ", request));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(syncService);
    }

    @Test
    @DisplayName("liderança sincroniza; o guarda é liberado no fim")
    void leaderSyncs() {
        leader();
        when(guard.tryAcquire("SQ")).thenReturn(true);
        when(syncService.sync("SQ", "u1")).thenReturn(new PlannerBacklogSyncService.SyncResult("T", 1, 0, 2, false));
        assertEquals(2, controller.syncBacklog("SQ", request).getBody().subtasks());
        verify(guard).release("SQ");
    }

    @Test
    @DisplayName("sync já em andamento na squad -> 409")
    void concurrentSync409() {
        leader();
        when(guard.tryAcquire("SQ")).thenReturn(false);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.syncBacklog("SQ", request));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verifyNoInteractions(syncService);
    }

    @Test
    @DisplayName("limite de 5 min: o 429 do service chega ao cliente e o guarda é liberado")
    void rateLimit429() {
        leader();
        when(guard.tryAcquire("SQ")).thenReturn(true);
        when(syncService.sync("SQ", "u1")).thenThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Tente de novo em 200 s"));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.syncBacklog("SQ", request));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
        verify(guard).release("SQ");
    }

    @Test
    @DisplayName("membro lê backlog e tipos; fora da squad -> 403")
    void readAccess() {
        member();
        controller.getBacklog("SQ", null, null, false, request);
        verify(backlogService).view("SQ", null, null, false);
        assertEquals(false, controller.getSubtaskTypes("SQ", null, request).getBody().get("canEdit"));

        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(access).requireSquadReadAccess("OUTRA", request);
        assertThrows(ResponseStatusException.class, () -> controller.getBacklog("OUTRA", null, null, false, request));
        assertThrows(ResponseStatusException.class, () -> controller.getKnownTypes("OUTRA", request));
    }

    @Test
    @DisplayName("membro não edita o mapeamento (403); liderança edita")
    void mappingWrite() {
        member();
        SprintPlannerBacklogController.SubtaskTypesUpdate body = new SprintPlannerBacklogController.SubtaskTypesUpdate("501",
                List.of(new SubtaskTypeMappingService.UpdateRequest("10999", null, SubtaskClass.QA, null, null)));
        assertThrows(ResponseStatusException.class, () -> controller.putSubtaskTypes("SQ", body, request));
        verify(mappingService, never()).update(any(), any(), any(), any());

        leader();
        controller.putSubtaskTypes("SQ", body, request);
        verify(mappingService).update(eq("SQ"), eq("501"), any(), eq("u1"));
    }
}
