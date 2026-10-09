package com.agilespace.backend.controller;

import com.agilespace.backend.domain.HealthCheckBoard;
import com.agilespace.backend.domain.HealthCheckParticipant;
import com.agilespace.backend.domain.HealthCheckVote;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.CeremonyCaller;
import com.agilespace.backend.service.HealthCheckService;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class HealthCheckControllerTest {

    @Mock
    private HealthCheckService service;

    @Mock
    private SquadAccessService squadAccessService;

    @InjectMocks
    private HealthCheckController controller;

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
        when(service.listBoards("DDWMISSI")).thenReturn(Arrays.asList(new HealthCheckBoard()));

        ResponseEntity<List<HealthCheckBoard>> response = controller.listBoards("DDWMISSI", request);

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
    public void testGetBoard() {
        when(service.getBoard("hc-1")).thenReturn(Optional.of(new HealthCheckBoard()));
        when(service.getBoard("hc-2")).thenReturn(Optional.empty());

        assertEquals(HttpStatus.OK, controller.getBoard("hc-1").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.getBoard("hc-2").getStatusCode());
    }

    @Test
    public void testSaveBoard_createdReturns201() {
        HealthCheckBoard board = new HealthCheckBoard();
        when(service.saveOrUpdateBoard(eq(board), any(CeremonyCaller.class)))
                .thenReturn(new HealthCheckService.Saved<>(board, true));

        assertEquals(HttpStatus.CREATED, controller.saveOrUpdateBoard(board, request("u1", "MEMBER")).getStatusCode());
    }

    @Test
    public void testFinish_usesCallerFromToken() {
        HealthCheckBoard board = new HealthCheckBoard();
        when(service.finish(eq("hc-1"), any(CeremonyCaller.class))).thenReturn(board);

        assertEquals(HttpStatus.OK, controller.finish("hc-1", request("u1", "MEMBER")).getStatusCode());
        ArgumentCaptor<CeremonyCaller> caller = ArgumentCaptor.forClass(CeremonyCaller.class);
        verify(service).finish(eq("hc-1"), caller.capture());
        assertEquals("u1", caller.getValue().id());
    }

    @Test
    public void testJoinBoard_boardIdComesFromPath() {
        HealthCheckParticipant participant = new HealthCheckParticipant();
        when(service.joinBoard(eq("hc-1"), eq(participant), any(CeremonyCaller.class))).thenReturn(participant);

        ResponseEntity<HealthCheckParticipant> response = controller.joinBoard("hc-1", participant, request("u1", "MEMBER"));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
    }

    @Test
    public void testGetVotes_participantIdParamDoesNotWidenResult() {
        when(service.getVotes(eq("hc-1"), any(CeremonyCaller.class))).thenReturn(Arrays.asList(new HealthCheckVote()));

        ResponseEntity<List<HealthCheckVote>> response = controller.getVotes("hc-1", "outra-pessoa", request("u1", "MEMBER"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<CeremonyCaller> caller = ArgumentCaptor.forClass(CeremonyCaller.class);
        verify(service).getVotes(eq("hc-1"), caller.capture());
        assertEquals("u1", caller.getValue().id());
    }

    @Test
    public void testSaveVote_delegatesWithCaller() {
        HealthCheckVote vote = new HealthCheckVote();
        when(service.saveVote(eq("hc-1"), eq(vote), any(CeremonyCaller.class))).thenReturn(vote);

        assertEquals(HttpStatus.CREATED, controller.saveVote("hc-1", vote, request("u1", "MEMBER")).getStatusCode());
    }
}
