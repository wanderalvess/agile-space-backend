package com.agilespace.backend.controller;

import com.agilespace.backend.domain.ActionPlan;
import com.agilespace.backend.domain.ActionPlanTask;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.ActionPlanService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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

    private HttpServletRequest mockRequest(String userId, String role) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn(userId);
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE)).thenReturn(role);
        return request;
    }

    @Test
    public void testCreateBoard() {
        ActionPlan board = new ActionPlan();
        when(service.createBoard(board)).thenReturn(board);

        ResponseEntity<ActionPlan> response = controller.createBoard(board, mockRequest("u1", "MEMBER"));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("u1", board.getCreatorId(), "creatorId deve vir do token, nunca do corpo");
        verify(service, times(1)).createBoard(board);
    }

    @Test
    public void testGetBoardByIdSuccess() {
        UUID id = UUID.randomUUID();
        ActionPlan board = new ActionPlan(); // isPublic=true por padrão
        when(service.getBoardById(id)).thenReturn(board);

        ResponseEntity<ActionPlan> response = controller.getBoardById(id, mockRequest("u1", "MEMBER"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testGetBoardByIdNotFound() {
        UUID id = UUID.randomUUID();
        when(service.getBoardById(id)).thenThrow(new IllegalArgumentException());

        // requireBoardAccess já resolve "board inexistente" como ResponseStatusException(NOT_FOUND)
        // antes mesmo do catch(IllegalArgumentException) do controller ser alcançado — em produção o
        // Spring converte isso pra 404 normalmente, mas nesse teste unitário (sem dispatcher) a
        // exceção se propaga.
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getBoardById(id, mockRequest("u1", "MEMBER")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    public void testGetPrivateBoardRejectsNonParticipant() {
        UUID id = UUID.randomUUID();
        ActionPlan board = new ActionPlan();
        board.setIsPublic(false);
        board.setCreatorId("dono");
        when(service.getBoardById(id)).thenReturn(board);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getBoardById(id, mockRequest("intruso", "MEMBER")));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    public void testGetPrivateBoardAllowsParticipant() {
        UUID id = UUID.randomUUID();
        ActionPlan board = new ActionPlan();
        board.setIsPublic(false);
        board.setCreatorId("dono");
        board.setParticipantIds(Set.of("participante"));
        when(service.getBoardById(id)).thenReturn(board);

        ResponseEntity<ActionPlan> response = controller.getBoardById(id, mockRequest("participante", "MEMBER"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testListBoardsBySprintId() {
        ActionPlan board = new ActionPlan();
        when(service.listBoardsBySprintId("SPRINT-1")).thenReturn(Arrays.asList(board));

        ResponseEntity<List<ActionPlan>> response = controller.listBoards("SPRINT-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
    }

    @Test
    public void testListBoardsWithoutSprintIdReturnsEmpty() {
        ResponseEntity<List<ActionPlan>> response = controller.listBoards(null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().size());
        verify(service, never()).listBoardsBySprintId(any());
    }

    @Test
    public void testListTasks() {
        UUID id = UUID.randomUUID();
        ActionPlan board = new ActionPlan();
        when(service.getBoardById(id)).thenReturn(board);
        when(service.listTasks(id)).thenReturn(Arrays.asList(new ActionPlanTask()));

        ResponseEntity<List<ActionPlanTask>> response = controller.listTasks(id, mockRequest("u1", "MEMBER"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
    }

    @Test
    public void testAddParticipantRejectsNonParticipantOnPrivateBoard() {
        UUID id = UUID.randomUUID();
        ActionPlan board = new ActionPlan();
        board.setIsPublic(false);
        board.setCreatorId("dono");
        when(service.getBoardById(id)).thenReturn(board);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.addParticipant(id, "intruso", mockRequest("intruso", "MEMBER")));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(service, never()).addParticipant(any(), any());
    }
}
