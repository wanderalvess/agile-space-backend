package com.agilespace.backend.controller;

import com.agilespace.backend.domain.ActionPlan;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.ActionPlanService;
import com.agilespace.backend.service.ActionPlanSourceConflictException;
import com.agilespace.backend.service.SquadAccessService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Origem do plano (V78) na camada HTTP: autorização na criação, 409 com existingPlanId e filtros da lista. */
class ActionPlanOriginControllerTest {

    @Mock private ActionPlanService service;
    @Mock private SquadAccessService squadAccessService;
    @InjectMocks private ActionPlanController controller;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
    }

    private HttpServletRequest request(String userId, String role) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn(userId);
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE)).thenReturn(role);
        return request;
    }

    private ActionPlan existing(boolean isPublic, String creator) {
        return ActionPlan.builder().id(UUID.randomUUID()).title("Antigo").creatorId(creator).isPublic(isPublic)
                .participantIds(Set.of()).build();
    }

    // ---------- criação: autorização ----------

    @Test
    void createInSquadRequiresSquadReadAccess() {
        ActionPlan body = ActionPlan.builder().title("P").squadId("SQA").sourceType("RETRO").sourceId("r1").build();
        when(service.resolveSquadId(body)).thenReturn("SQA");
        when(service.createBoard(body)).thenReturn(body);

        ResponseEntity<ActionPlan> response = controller.createBoard(body, request("u1", "MEMBER"));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        verify(squadAccessService).requireSquadReadAccess(eq("SQA"), any());
    }

    @Test
    void createInForeignSquadIs403BeforeAnyDuplicateCheck() {
        ActionPlan body = ActionPlan.builder().title("P").squadId("SQX").sourceType("RETRO").sourceId("r1").build();
        when(service.resolveSquadId(body)).thenReturn("SQX");
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(squadAccessService).requireSquadReadAccess(eq("SQX"), any());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.createBoard(body, request("u1", "MEMBER")));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(service, never()).createBoard(any());
    }

    @Test
    void createWithFreeTextTeamDoesNotCheckSquad() {
        ActionPlan body = ActionPlan.builder().title("P").team("Squad Geral").build();
        when(service.resolveSquadId(body)).thenReturn(null);
        when(service.createBoard(body)).thenReturn(body);

        controller.createBoard(body, request("u1", "MEMBER"));

        verifyNoInteractions(squadAccessService);
    }

    // ---------- 409 ----------

    @Test
    void conflictBodyCarriesExistingPlanIdWhenVisible() {
        ActionPlan found = existing(true, "outro");

        ResponseEntity<Map<String, String>> response = controller.handleSourceConflict(
                new ActionPlanSourceConflictException(found), request("u1", "MEMBER"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(found.getId().toString(), response.getBody().get("existingPlanId"));
        assertNotNull(response.getBody().get("message"));
        assertNotNull(response.getBody().get("error"));
    }

    @Test
    void conflictOnForeignPrivatePlanDoesNotLeakItsId() {
        ActionPlan found = existing(false, "outro");

        ResponseEntity<Map<String, String>> response = controller.handleSourceConflict(
                new ActionPlanSourceConflictException(found), request("u1", "MEMBER"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertFalse(response.getBody().containsKey("existingPlanId"));
    }

    @Test
    void conflictOnPrivatePlanShowsIdToCreatorAndAdmin() {
        ActionPlan found = existing(false, "u1");
        assertTrue(controller.handleSourceConflict(new ActionPlanSourceConflictException(found), request("u1", "MEMBER"))
                .getBody().containsKey("existingPlanId"));
        assertTrue(controller.handleSourceConflict(new ActionPlanSourceConflictException(found), request("adm", "ADMIN"))
                .getBody().containsKey("existingPlanId"));
    }

    @Test
    void raceOnUniqueIndexBecomesConflictWithExistingPlan() {
        ActionPlan body = ActionPlan.builder().title("P").squadId("SQA").sourceType("RETRO").sourceId("r1").build();
        ActionPlan winner = existing(true, "outro");
        when(service.resolveSquadId(body)).thenReturn("SQA");
        when(service.createBoard(body)).thenThrow(new DataIntegrityViolationException("uq_action_plans_source"));
        when(service.findExistingBySource("SQA", "RETRO", "r1")).thenReturn(Optional.of(winner));
        body.setSquadId("SQA");

        ActionPlanSourceConflictException ex = assertThrows(ActionPlanSourceConflictException.class,
                () -> controller.createBoard(body, request("u1", "MEMBER")));

        assertSame(winner, ex.getExisting());
    }

    @Test
    void integrityViolationWithoutADuplicateIsRethrown() {
        ActionPlan body = ActionPlan.builder().title("P").build();
        when(service.createBoard(body)).thenThrow(new DataIntegrityViolationException("outra coisa"));
        when(service.findExistingBySource(any(), any(), any())).thenReturn(Optional.empty());

        assertThrows(DataIntegrityViolationException.class, () -> controller.createBoard(body, request("u1", "MEMBER")));
    }

    // ---------- lista: filtros ----------

    @Test
    void listBySquadWithSourceFilterUsesFilteredQueryAndHidesPrivate() {
        ActionPlan pub = existing(true, "outro");
        ActionPlan priv = existing(false, "outro");
        when(service.listBoardsBySquad("SQA", "HEALTH_CHECK", "hc-1")).thenReturn(List.of(pub, priv));

        ResponseEntity<List<ActionPlan>> response = controller.listBoards(null, "SQA", "HEALTH_CHECK", "hc-1", request("u1", "MEMBER"));

        assertEquals(List.of(pub), response.getBody());
        verify(squadAccessService).requireSquadReadAccess(eq("SQA"), any());
        verify(service, never()).listBoardsBySquad("SQA");
    }

    @Test
    void listBySquadWithoutFilterKeepsTheOldCall() {
        when(service.listBoardsBySquad("SQA")).thenReturn(List.of());
        controller.listBoards(null, "SQA", null, null, request("u1", "MEMBER"));
        verify(service).listBoardsBySquad("SQA");
        verify(service, never()).listBoardsBySquad(eq("SQA"), any(), any());
    }

    @Test
    void sourceIdWithoutTypeIs400() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listBoards(null, "SQA", null, "hc-1", request("u1", "MEMBER")));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void searchBySourceWithoutSquadKeepsOnlyVisiblePlansFromReadableSquads() {
        ActionPlan noSquad = existing(true, "outro");
        ActionPlan mine = existing(true, "outro");
        mine.setSquadId("SQA");
        ActionPlan foreign = existing(true, "outro");
        foreign.setSquadId("SQX");
        ActionPlan foreignPrivate = existing(false, "outro");
        when(service.listBoardsBySource("HEALTH_CHECK", "hc-1")).thenReturn(List.of(noSquad, mine, foreign, foreignPrivate));
        HttpServletRequest req = request("u1", "MEMBER");
        when(squadAccessService.matchesSquad("SQA", req)).thenReturn(true);
        when(squadAccessService.matchesSquad("SQX", req)).thenReturn(false);

        ResponseEntity<List<ActionPlan>> response = controller.listBoards(null, null, "HEALTH_CHECK", "hc-1", req);

        assertEquals(List.of(noSquad, mine), response.getBody());
    }

    @Test
    void sourceTypeAloneWithoutSquadOrSprintIsEmpty() {
        ResponseEntity<List<ActionPlan>> response = controller.listBoards(null, null, "RETRO", null, request("u1", "MEMBER"));
        assertTrue(response.getBody().isEmpty());
        verifyNoInteractions(service);
    }

    @Test
    void sprintListIgnoresSourceFilters() {
        ActionPlan pub = existing(true, "outro");
        when(service.listBoardsBySprintId("S1")).thenReturn(List.of(pub));

        ResponseEntity<List<ActionPlan>> response = controller.listBoards("S1", null, "RETRO", "r1", request("u1", "MEMBER"));

        assertEquals(List.of(pub), response.getBody());
        verify(service, never()).listBoardsBySource(any(), any());
    }
}
