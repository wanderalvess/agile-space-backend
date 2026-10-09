package com.agilespace.backend.controller;

import com.agilespace.backend.domain.RetroBoard;
import com.agilespace.backend.domain.RetroParticipant;
import com.agilespace.backend.domain.RetroCard;
import com.agilespace.backend.domain.RetroChatMessage;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.RetroCaller;
import com.agilespace.backend.service.RetroService;
import com.agilespace.backend.service.SquadAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class RetroControllerTest {

    @Mock
    private RetroService service;

    @Mock
    private SquadAccessService squadAccessService;

    @InjectMocks
    private RetroController controller;

    private static final RetroCaller ANA = new RetroCaller("ana", "MEMBER");

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    private HttpServletRequest requestOf(String userId, String role) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn(userId);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE)).thenReturn(role);
        return request;
    }

    @Test
    public void testGetBoard() {
        RetroBoard board = new RetroBoard();
        when(service.getBoard("123", ANA)).thenReturn(Optional.of(board));

        ResponseEntity<RetroBoard> response = controller.getBoard("123", requestOf("ana", "MEMBER"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testGetBoard_notFound() {
        when(service.getBoard("nada", ANA)).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller.getBoard("nada", requestOf("ana", "MEMBER")).getStatusCode());
    }

    @Test
    public void testGetBoard_forbiddenPropagates() {
        when(service.getBoard("123", ANA)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));

        assertThrows(ResponseStatusException.class, () -> controller.getBoard("123", requestOf("ana", "MEMBER")));
    }

    @Test
    public void testSaveOrUpdateBoard_createdIs201_updateIs200() {
        RetroBoard board = new RetroBoard();
        HttpServletRequest request = requestOf("ana", "MEMBER");
        when(service.saveOrUpdateBoard(board, ANA)).thenReturn(new RetroService.Saved<>(board, true));
        assertEquals(HttpStatus.CREATED, controller.saveOrUpdateBoard(board, request).getStatusCode());

        when(service.saveOrUpdateBoard(board, ANA)).thenReturn(new RetroService.Saved<>(board, false));
        assertEquals(HttpStatus.OK, controller.saveOrUpdateBoard(board, request).getStatusCode());
    }

    @Test
    public void testPatchBoard_passesBodyAndCaller() throws Exception {
        JsonNode patch = new ObjectMapper().readTree("{\"votingStatus\":\"active\"}");
        RetroBoard board = new RetroBoard();
        when(service.patchBoard("123", patch, ANA)).thenReturn(board);

        ResponseEntity<RetroBoard> response = controller.patchBoard("123", patch, requestOf("ana", "MEMBER"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(board, response.getBody());
    }

    @Test
    public void testPatchBoard_forbiddenPropagates() throws Exception {
        JsonNode patch = new ObjectMapper().readTree("{\"title\":\"x\"}");
        when(service.patchBoard("123", patch, ANA)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));

        assertThrows(ResponseStatusException.class, () -> controller.patchBoard("123", patch, requestOf("ana", "MEMBER")));
    }

    @Test
    public void testTransferControlAndResetVotes() {
        RetroBoard board = new RetroBoard();
        when(service.transferControl("123", ANA)).thenReturn(board);
        when(service.resetVotes("123", ANA)).thenReturn(board);
        HttpServletRequest request = requestOf("ana", "MEMBER");

        assertEquals(HttpStatus.OK, controller.transferControl("123", request).getStatusCode());
        assertEquals(HttpStatus.OK, controller.resetVotes("123", request).getStatusCode());
    }

    @Test
    public void testVote_usesCallerFromJwt() {
        RetroCard card = new RetroCard();
        when(service.toggleVote("123", "c1", ANA)).thenReturn(card);

        ResponseEntity<RetroCard> response = controller.vote("123", "c1", requestOf("ana", "MEMBER"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(card, response.getBody());
        verify(service).toggleVote("123", "c1", ANA);
    }

    @Test
    public void testVote_conflictPropagates() {
        when(service.toggleVote("123", "c1", ANA)).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "fechada"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.vote("123", "c1", requestOf("ana", "MEMBER")));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    public void testMerge() {
        RetroCard target = new RetroCard();
        when(service.mergeCards("123", "t", "s", ANA)).thenReturn(target);

        ResponseEntity<RetroCard> response = controller.mergeCards("123", "t", "s", requestOf("ana", "MEMBER"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(target, response.getBody());
    }

    @Test
    public void testAddOrUpdateParticipant() {
        RetroParticipant participant = new RetroParticipant();
        when(service.addOrUpdateParticipant(participant, ANA)).thenReturn(new RetroService.Saved<>(participant, true));

        ResponseEntity<RetroParticipant> response = controller.addOrUpdateParticipant("123", participant, requestOf("ana", "MEMBER"));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("123", participant.getBoardId());
    }

    @Test
    public void testSaveOrUpdateCard_createdIs201_updateIs200() {
        RetroCard card = new RetroCard();
        HttpServletRequest request = requestOf("ana", "MEMBER");
        when(service.saveOrUpdateCard(card, ANA)).thenReturn(new RetroService.Saved<>(card, true));
        assertEquals(HttpStatus.CREATED, controller.saveOrUpdateCard("123", card, request).getStatusCode());
        assertEquals("123", card.getBoardId());

        when(service.saveOrUpdateCard(card, ANA)).thenReturn(new RetroService.Saved<>(card, false));
        assertEquals(HttpStatus.OK, controller.saveOrUpdateCard("123", card, request).getStatusCode());
    }

    @Test
    public void testListBoardsBySprintId_filtersByCaller() {
        RetroBoard board = new RetroBoard();
        when(service.listBoardsBySprintId("SPRINT-1", ANA)).thenReturn(Arrays.asList(board));

        ResponseEntity<List<RetroBoard>> response = controller.listBoards("SPRINT-1", null, null, requestOf("ana", "MEMBER"));

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
        verify(service, never()).listBoardsBySquadId(any());
    }

    @Test
    public void testListBoards_withoutAnyFilter_badRequest() {
        assertThrows(ResponseStatusException.class, () -> controller.listBoards(null, null, null, null));
        verifyNoInteractions(service);
    }

    @Test
    public void testGetCardsAndParticipants_forbiddenForOutsider() {
        RetroCaller intruso = new RetroCaller("intruso", "MEMBER");
        when(service.getCards("123", intruso)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        when(service.getParticipants("123", intruso)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        HttpServletRequest request = requestOf("intruso", "MEMBER");

        assertThrows(ResponseStatusException.class, () -> controller.getCards("123", request));
        assertThrows(ResponseStatusException.class, () -> controller.getParticipants("123", request));
    }

    @Test
    public void testDeleteCard() {
        HttpServletRequest request = requestOf("root", "ADMIN");

        ResponseEntity<Void> response = controller.deleteCard("123", "card1", request);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(service).deleteCard("123", "card1", new RetroCaller("root", "ADMIN"));
    }

    @Test
    public void testDeleteCard_notFoundPropagates() {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
                .when(service).deleteCard("123", "zz", ANA);

        assertThrows(ResponseStatusException.class, () -> controller.deleteCard("123", "zz", requestOf("ana", "MEMBER")));
    }

    @Test
    public void testRemoveParticipantAndDeleteBoard() {
        HttpServletRequest request = requestOf("ana", "MEMBER");

        assertEquals(HttpStatus.NO_CONTENT, controller.removeParticipant("123", "ana", request).getStatusCode());
        assertEquals(HttpStatus.NO_CONTENT, controller.deleteBoard("123", request).getStatusCode());
        verify(service).removeParticipant("123", "ana", ANA);
        verify(service).deleteBoard("123", ANA);
    }

    @Test
    public void testImportActions() {
        HttpServletRequest request = requestOf("ana", "MEMBER");
        List<RetroCard> cards = Arrays.asList(new RetroCard());

        assertEquals(HttpStatus.OK, controller.importActions("123", cards, request).getStatusCode());
        verify(service).importActions("123", cards, ANA);
    }

    @Test
    public void testErrorHandlers_mapTo409And400() {
        assertEquals(HttpStatus.CONFLICT,
                controller.handleOptimisticLock(new OptimisticLockingFailureException("x")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                controller.handleDataIntegrity(new DataIntegrityViolationException("x")).getStatusCode());
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
