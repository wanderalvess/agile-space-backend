package com.agilespace.backend.controller;

import com.agilespace.backend.domain.RetroBoard;
import com.agilespace.backend.domain.RetroParticipant;
import com.agilespace.backend.domain.RetroCard;
import com.agilespace.backend.domain.RetroChatMessage;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.RetroService;
import com.agilespace.backend.service.SquadAccessService;
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
import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

public class RetroControllerTest {

    @Mock
    private RetroService service;

    @Mock
    private SquadAccessService squadAccessService;

    @InjectMocks
    private RetroController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testGetBoard() {
        RetroBoard board = new RetroBoard();
        when(service.getBoard("123")).thenReturn(Optional.of(board));
        
        ResponseEntity<RetroBoard> response = controller.getBoard("123");
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testSaveOrUpdateBoard() {
        RetroBoard board = new RetroBoard();
        when(service.saveOrUpdateBoard(board)).thenReturn(board);
        
        ResponseEntity<RetroBoard> response = controller.saveOrUpdateBoard(board);
        
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
    }

    @Test
    public void testAddOrUpdateParticipant() {
        RetroParticipant participant = new RetroParticipant();
        when(service.addOrUpdateParticipant(participant)).thenReturn(participant);
        
        ResponseEntity<RetroParticipant> response = controller.addOrUpdateParticipant("123", participant);
        
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("123", participant.getBoardId());
    }

    @Test
    public void testSaveOrUpdateCard() {
        RetroCard card = new RetroCard();
        when(service.saveOrUpdateCard(card)).thenReturn(card);
        
        ResponseEntity<RetroCard> response = controller.saveOrUpdateCard("123", card);
        
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("123", card.getBoardId());
    }

    @Test
    public void testListBoardsBySprintId() {
        RetroBoard board = new RetroBoard();
        when(service.listBoardsBySprintId("SPRINT-1")).thenReturn(Arrays.asList(board));

        ResponseEntity<List<RetroBoard>> response = controller.listBoards("SPRINT-1", null, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        verifyNoInteractions(squadAccessService);
    }

    @Test
    public void testListBoardsBySquadId_checksAccess() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(service.listBoardsBySquadId("DDWMISSI")).thenReturn(Arrays.asList(new RetroBoard()));

        ResponseEntity<List<RetroBoard>> response = controller.listBoards(null, null, "DDWMISSI", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(squadAccessService).requireSquadReadAccess("DDWMISSI", request);
    }

    @Test
    public void testListBoards_squadIdNotOwn_forbidden() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(squadAccessService).requireSquadReadAccess("outra-squad", request);

        assertThrows(ResponseStatusException.class, () -> controller.listBoards(null, null, "outra-squad", request));
        verify(service, never()).listBoardsBySquadId(anyString());
    }

    @Test
    public void testListBoards_withoutAnyFilter_badRequest() {
        assertThrows(ResponseStatusException.class, () -> controller.listBoards(null, null, null, null));
        verifyNoInteractions(service);
    }

    @Test
    public void testDeleteCard() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE)).thenReturn("ADMIN");
        doNothing().when(service).deleteCard("123", "card1");

        ResponseEntity<Void> response = controller.deleteCard("123", "card1", request);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    public void testGetChatMessages_usesCallerFromRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("ana");
        List<RetroChatMessage> list = Arrays.asList(new RetroChatMessage());
        when(service.getChatMessages("123", "geral", "ana")).thenReturn(list);

        ResponseEntity<List<RetroChatMessage>> response = controller.getChatMessages("123", "geral", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(list, response.getBody());
    }

    @Test
    public void testSendChatMessage_returnsCreated() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("ana");
        RetroChatMessage message = new RetroChatMessage();
        when(service.saveChatMessage("123", message, "ana")).thenReturn(message);

        ResponseEntity<RetroChatMessage> response = controller.sendChatMessage("123", message, request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(message, response.getBody());
    }

    @Test
    public void testSendChatMessage_propagatesForbidden() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("intruso");
        RetroChatMessage message = new RetroChatMessage();
        when(service.saveChatMessage("123", message, "intruso"))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));

        assertThrows(ResponseStatusException.class, () -> controller.sendChatMessage("123", message, request));
    }

    @Test
    public void testDeleteChatMessage_returnsNoContent() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("ana");

        ResponseEntity<Void> response = controller.deleteChatMessage("123", "m1", request);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(service).deleteChatMessage("123", "m1", "ana");
    }
}
