package com.agilespace.backend.controller;

import com.agilespace.backend.domain.BrainstormingBoard;
import com.agilespace.backend.domain.BrainstormingIdea;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.BrainstormingService;
import com.agilespace.backend.service.CeremonyCaller;
import com.agilespace.backend.service.SquadAccessService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class BrainstormingControllerTest {

    @Mock
    private BrainstormingService service;

    @Mock
    private SquadAccessService squadAccessService;

    @InjectMocks
    private BrainstormingController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    private HttpServletRequest request(String userId, String role) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn(userId);
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE)).thenReturn(role);
        return request;
    }

    @Test
    public void testListBoards_filtersBySquadAndChecksAccess() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(service.listBoards("DDWMISSI")).thenReturn(Arrays.asList(new BrainstormingBoard()));

        ResponseEntity<List<BrainstormingBoard>> response = controller.listBoards("DDWMISSI", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        verify(squadAccessService).requireSquadReadAccess("DDWMISSI", request);
    }

    @Test
    public void testListBoards_notMemberOfSquad_forbidden() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(squadAccessService).requireSquadReadAccess("outra-squad", request);

        assertThrows(ResponseStatusException.class, () -> controller.listBoards("outra-squad", request));
        verify(service, never()).listBoards(anyString());
    }

    @Test
    public void testGetBoardFoundAndNotFound() {
        when(service.getBoard("123")).thenReturn(Optional.of(new BrainstormingBoard()));
        when(service.getBoard("999")).thenReturn(Optional.empty());

        assertEquals(HttpStatus.OK, controller.getBoard("123").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.getBoard("999").getStatusCode());
    }

    @Test
    public void testSaveBoard_passesCallerFromTokenAndReturns201OnCreate() {
        BrainstormingBoard board = new BrainstormingBoard();
        when(service.saveOrUpdateBoard(eq(board), any(CeremonyCaller.class)))
                .thenReturn(new BrainstormingService.Saved<>(board, true));

        ResponseEntity<BrainstormingBoard> response = controller.saveOrUpdateBoard(board, request("u1", "MEMBER"));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        ArgumentCaptor<CeremonyCaller> caller = ArgumentCaptor.forClass(CeremonyCaller.class);
        verify(service).saveOrUpdateBoard(eq(board), caller.capture());
        assertEquals("u1", caller.getValue().id());
        assertEquals("MEMBER", caller.getValue().role());
    }

    @Test
    public void testSaveIdea_updateReturns200() {
        BrainstormingIdea idea = new BrainstormingIdea();
        when(service.saveOrUpdateIdea(eq("b1"), eq(idea), any(CeremonyCaller.class)))
                .thenReturn(new BrainstormingService.Saved<>(idea, false));

        assertEquals(HttpStatus.OK, controller.saveOrUpdateIdea("b1", idea, request("u1", "MEMBER")).getStatusCode());
    }

    @Test
    public void testToggleVote_usesIdentityFromToken() {
        BrainstormingIdea idea = new BrainstormingIdea();
        when(service.toggleVote(eq("b1"), eq("i1"), any(CeremonyCaller.class))).thenReturn(idea);

        ResponseEntity<BrainstormingIdea> response = controller.toggleVote("b1", "i1", request("u7", "MEMBER"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<CeremonyCaller> caller = ArgumentCaptor.forClass(CeremonyCaller.class);
        verify(service).toggleVote(eq("b1"), eq("i1"), caller.capture());
        assertEquals("u7", caller.getValue().id());
    }

    @Test
    public void testDeleteBoard_delegatesAuthorizationToService() {
        ResponseEntity<Void> response = controller.deleteBoard("123", request("u1", "MEMBER"));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(service).deleteBoard(eq("123"), any(CeremonyCaller.class));
    }

    @Test
    public void testErrorHandler_keepsPortugueseMessage() {
        ResponseEntity<Map<String, String>> response = controller.handleStatus(
                new ResponseStatusException(HttpStatus.FORBIDDEN, "Apenas o facilitador pode alterar a sessão."));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("Apenas o facilitador pode alterar a sessão.", response.getBody().get("message"));
    }
}
