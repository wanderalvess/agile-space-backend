package com.agilespace.backend.service;

import com.agilespace.backend.domain.SupportTicket;
import com.agilespace.backend.domain.SupportTicketReply;
import com.agilespace.backend.repository.SupportTicketReplyRepository;
import com.agilespace.backend.repository.SupportTicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class SupportTicketServiceTest {

    @Mock
    private SupportTicketRepository ticketRepository;

    @Mock
    private SupportTicketReplyRepository replyRepository;

    @InjectMocks
    private SupportTicketService service;

    private final UUID ticketId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        lenient().when(ticketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(replyRepository.save(any(SupportTicketReply.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private SupportTicket ticket(String status) {
        return SupportTicket.builder()
                .id(ticketId)
                .subject("Erro no Poker")
                .message("Nao consigo votar")
                .status(status)
                .requesterId("u1")
                .build();
    }

    // ---------- listagens ----------

    @Test
    public void testListMyTicketsIsScopedToRequester() {
        when(ticketRepository.findByRequesterIdOrderByCreatedAtDesc("u1")).thenReturn(List.of(ticket("OPEN")));

        assertEquals(1, service.listMyTickets("u1").size());
        verify(ticketRepository).findByRequesterIdOrderByCreatedAtDesc("u1");
        verify(ticketRepository, never()).findAllByOrderByCreatedAtDesc();
    }

    @Test
    public void testListAllTicketsWithoutFilterReturnsEverything() {
        when(ticketRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(ticket("OPEN"), ticket("CLOSED")));

        assertEquals(2, service.listAllTickets(null).size());
        assertEquals(2, service.listAllTickets("   ").size());
        verify(ticketRepository, times(2)).findAllByOrderByCreatedAtDesc();
    }

    @Test
    public void testListAllTicketsAppliesStatusFilter() {
        when(ticketRepository.findByStatusOrderByCreatedAtDesc("OPEN")).thenReturn(List.of(ticket("OPEN")));

        assertEquals(1, service.listAllTickets("OPEN").size());
        verify(ticketRepository).findByStatusOrderByCreatedAtDesc("OPEN");
        verify(ticketRepository, never()).findAllByOrderByCreatedAtDesc();
    }

    // ---------- createTicket ----------

    @Test
    public void testCreateTicketOverwritesClientSuppliedIdentityWithAuthenticatedUser() {
        SupportTicket payload = SupportTicket.builder()
                .subject("Erro")
                .message("Detalhe")
                .requesterId("usuario-forjado")
                .requesterEmail("forjado@empresa.com.br")
                .status("CLOSED")
                .build();

        SupportTicket created = service.createTicket("u1", "Joao Silva", "joao@empresa.com.br", payload);

        assertEquals("u1", created.getRequesterId());
        assertEquals("Joao Silva", created.getRequesterName());
        assertEquals("joao@empresa.com.br", created.getRequesterEmail());
        // Status inicial e sempre OPEN, cliente nao escolhe.
        assertEquals("OPEN", created.getStatus());
        assertNotNull(created.getUpdatedAt());
    }

    // ---------- updateStatus ----------

    @Test
    public void testUpdateStatusPersistsNewStatusAndTimestamp() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket("OPEN")));

        SupportTicket updated = service.updateStatus(ticketId, "IN_PROGRESS");

        assertEquals("IN_PROGRESS", updated.getStatus());
        assertNotNull(updated.getUpdatedAt());
    }

    @Test
    public void testUpdateStatusUnknownTicketThrows() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.updateStatus(ticketId, "CLOSED"));
        verify(ticketRepository, never()).save(any());
    }

    // ---------- addReply ----------

    @Test
    public void testAddReplyPersistsReplyAndTouchesTicket() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket("OPEN")));

        SupportTicketReply reply = service.addReply(ticketId, "admin1", "Suporte", true, "Estamos verificando");

        assertEquals(ticketId, reply.getTicketId());
        assertEquals("admin1", reply.getAuthorId());
        assertTrue(reply.isAdmin());
        assertEquals("Estamos verificando", reply.getMessage());
        verify(ticketRepository).save(any(SupportTicket.class));
    }

    @Test
    public void testAddReplyFromRequesterIsNotFlaggedAsAdmin() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket("OPEN")));

        SupportTicketReply reply = service.addReply(ticketId, "u1", "Joao", false, "Segue o print");

        assertFalse(reply.isAdmin());
    }

    @Test
    public void testAddReplyUnknownTicketThrowsAndSavesNothing() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.addReply(ticketId, "u1", "Joao", false, "msg"));

        verify(replyRepository, never()).save(any());
        verify(ticketRepository, never()).save(any());
    }

    @Test
    public void testGetRepliesDelegatesInChronologicalOrder() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket("OPEN")));
        when(replyRepository.findByTicketIdOrderByCreatedAtAsc(ticketId))
                .thenReturn(List.of(SupportTicketReply.builder().ticketId(ticketId).build()));

        assertEquals(1, service.getReplies(ticketId, "u1", false).size());
        verify(replyRepository).findByTicketIdOrderByCreatedAtAsc(ticketId);
    }

    @Test
    public void testGetRepliesRejectsOtherRequester() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket("OPEN")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getReplies(ticketId, "intruso", false));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(replyRepository, never()).findByTicketIdOrderByCreatedAtAsc(any());
    }

    @Test
    public void testGetRepliesAllowsAdmin() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket("OPEN")));
        when(replyRepository.findByTicketIdOrderByCreatedAtAsc(ticketId)).thenReturn(List.of());

        assertTrue(service.getReplies(ticketId, "admin1", true).isEmpty());
    }

    @Test
    public void testGetRepliesUnknownTicketThrows() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.getReplies(ticketId, "u1", false));
    }

    @Test
    public void testAddReplyRejectsUserWhoDoesNotOwnTheTicket() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket("OPEN")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.addReply(ticketId, "intruso", "Intruso", false, "quero ver"));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(replyRepository, never()).save(any());
        verify(ticketRepository, never()).save(any());
    }

    // ---------- deleteTicket ----------

    @Test
    public void testDeleteTicketRemovesRepliesBeforeTicket() {
        when(ticketRepository.existsById(ticketId)).thenReturn(true);

        service.deleteTicket(ticketId);

        org.mockito.InOrder order = inOrder(replyRepository, ticketRepository);
        order.verify(replyRepository).deleteByTicketId(ticketId);
        order.verify(ticketRepository).deleteById(ticketId);
    }

    @Test
    public void testDeleteTicketUnknownThrowsAndKeepsReplies() {
        when(ticketRepository.existsById(ticketId)).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> service.deleteTicket(ticketId));

        verify(replyRepository, never()).deleteByTicketId(any());
        verify(ticketRepository, never()).deleteById(any());
    }
}
