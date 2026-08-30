package com.agilespace.backend.controller;

import com.agilespace.backend.domain.SupportTicket;
import com.agilespace.backend.domain.SupportTicketReply;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.SupportTicketService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class SupportTicketControllerTest {

    @Mock
    private SupportTicketService supportTicketService;

    @InjectMocks
    private SupportTicketController controller;

    private final UUID ticketId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    private HttpServletRequest requestAs(String userId, String email, String role) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn(userId);
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_EMAIL)).thenReturn(email);
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE)).thenReturn(role);
        return request;
    }

    private SupportTicket ticket() {
        return SupportTicket.builder().id(ticketId).subject("Erro").status("OPEN").build();
    }

    @Test
    public void testCreateTicketUsesAuthenticatedIdentity() {
        SupportTicket payload = SupportTicket.builder().subject("Erro").message("Detalhe").build();
        when(supportTicketService.createTicket(eq("u1"), anyString(), eq("joao@empresa.com.br"), eq(payload)))
                .thenReturn(ticket());

        ResponseEntity<SupportTicket> response = controller.createTicket(
                requestAs("u1", "joao@empresa.com.br", "MEMBER"), payload);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        verify(supportTicketService).createTicket("u1", "joao@empresa.com.br", "joao@empresa.com.br", payload);
    }

    @Test
    public void testCreateTicketKeepsProvidedRequesterName() {
        SupportTicket payload = SupportTicket.builder().subject("Erro").requesterName("Joao Silva").build();
        when(supportTicketService.createTicket(anyString(), anyString(), anyString(), any())).thenReturn(ticket());

        controller.createTicket(requestAs("u1", "joao@empresa.com.br", "MEMBER"), payload);

        verify(supportTicketService).createTicket("u1", "Joao Silva", "joao@empresa.com.br", payload);
    }

    @Test
    public void testListMyTicketsScopedToAuthenticatedUser() {
        when(supportTicketService.listMyTickets("u1")).thenReturn(List.of(ticket()));

        ResponseEntity<List<SupportTicket>> response = controller.listMyTickets(requestAs("u1", "joao@empresa.com.br", "MEMBER"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(supportTicketService).listMyTickets("u1");
    }

    @Test
    public void testListAllTicketsPassesStatusFilterThrough() {
        when(supportTicketService.listAllTickets("OPEN")).thenReturn(List.of(ticket()));

        assertEquals(HttpStatus.OK, controller.listAllTickets(requestAs("admin1", "admin@empresa.com.br", "ADMIN"), "OPEN").getStatusCode());
        verify(supportTicketService).listAllTickets("OPEN");
    }

    @Test
    public void testUpdateStatusReturnsUpdatedTicket() {
        when(supportTicketService.updateStatus(ticketId, "CLOSED")).thenReturn(ticket());

        assertEquals(HttpStatus.OK, controller.updateStatus(
                requestAs("admin1", "admin@empresa.com.br", "ADMIN"), ticketId, "CLOSED").getStatusCode());
    }

    @Test
    public void testUpdateStatusUnknownTicketReturnsNotFound() {
        when(supportTicketService.updateStatus(ticketId, "CLOSED")).thenThrow(new IllegalArgumentException("not found"));

        assertEquals(HttpStatus.NOT_FOUND, controller.updateStatus(
                requestAs("admin1", "admin@empresa.com.br", "ADMIN"), ticketId, "CLOSED").getStatusCode());
    }

    @Test
    public void testGetRepliesReturnsList() {
        when(supportTicketService.getReplies(ticketId, "u1", false))
                .thenReturn(List.of(SupportTicketReply.builder().ticketId(ticketId).build()));

        ResponseEntity<List<SupportTicketReply>> response = controller.getReplies(
                requestAs("u1", "joao@empresa.com.br", "MEMBER"), ticketId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
    }

    @Test
    public void testAddReplyMarksAdminFlagFromJwtRoleNotFromBody() {
        when(supportTicketService.addReply(eq(ticketId), anyString(), anyString(), anyBoolean(), anyString()))
                .thenReturn(SupportTicketReply.builder().ticketId(ticketId).build());

        controller.addReply(requestAs("u1", "joao@empresa.com.br", "MEMBER"), ticketId,
                Map.of("message", "oi", "isAdmin", "true"));

        // isAdmin vem do papel no JWT; o corpo da requisicao nao pode elevar privilegio.
        verify(supportTicketService).addReply(ticketId, "u1", "joao@empresa.com.br", false, "oi");
    }

    @Test
    public void testAddReplyFromAdminIsFlaggedAsAdmin() {
        when(supportTicketService.addReply(eq(ticketId), anyString(), anyString(), anyBoolean(), anyString()))
                .thenReturn(SupportTicketReply.builder().ticketId(ticketId).build());

        ResponseEntity<SupportTicketReply> response = controller.addReply(
                requestAs("admin1", "admin@empresa.com.br", "ADMIN"), ticketId,
                Map.of("message", "Resolvido", "authorName", "Suporte"));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        verify(supportTicketService).addReply(ticketId, "admin1", "Suporte", true, "Resolvido");
    }

    @Test
    public void testAddReplyUnknownTicketReturnsNotFound() {
        when(supportTicketService.addReply(any(), anyString(), anyString(), anyBoolean(), any()))
                .thenThrow(new IllegalArgumentException("not found"));

        ResponseEntity<SupportTicketReply> response = controller.addReply(
                requestAs("u1", "joao@empresa.com.br", "MEMBER"), ticketId, Map.of("message", "oi"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    public void testDeleteTicketReturnsNoContent() {
        assertEquals(HttpStatus.NO_CONTENT, controller.deleteTicket(
                requestAs("admin1", "admin@empresa.com.br", "ADMIN"), ticketId).getStatusCode());
        verify(supportTicketService).deleteTicket(ticketId);
    }

    @Test
    public void testDeleteUnknownTicketReturnsNotFound() {
        doThrow(new IllegalArgumentException("not found")).when(supportTicketService).deleteTicket(ticketId);

        assertEquals(HttpStatus.NOT_FOUND, controller.deleteTicket(
                requestAs("admin1", "admin@empresa.com.br", "ADMIN"), ticketId).getStatusCode());
    }

    // ---------- triagem restrita a ADMIN ----------

    @Test
    public void testListAllTicketsRejectsNonAdmin() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listAllTickets(requestAs("u1", "joao@empresa.com.br", "MEMBER"), null));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(supportTicketService);
    }

    @Test
    public void testUpdateStatusRejectsNonAdmin() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateStatus(requestAs("u1", "joao@empresa.com.br", "MEMBER"), ticketId, "CLOSED"));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(supportTicketService);
    }

    @Test
    public void testDeleteTicketRejectsNonAdmin() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteTicket(requestAs("u1", "joao@empresa.com.br", "MEMBER"), ticketId));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(supportTicketService);
    }

    @Test
    public void testGetRepliesPassesAdminFlagFromJwt() {
        when(supportTicketService.getReplies(ticketId, "admin1", true)).thenReturn(List.of());

        assertEquals(HttpStatus.OK, controller.getReplies(
                requestAs("admin1", "admin@empresa.com.br", "ADMIN"), ticketId).getStatusCode());
    }

    @Test
    public void testGetRepliesUnknownTicketReturnsNotFound() {
        when(supportTicketService.getReplies(any(), anyString(), anyBoolean()))
                .thenThrow(new IllegalArgumentException("not found"));

        assertEquals(HttpStatus.NOT_FOUND, controller.getReplies(
                requestAs("u1", "joao@empresa.com.br", "MEMBER"), ticketId).getStatusCode());
    }
}
