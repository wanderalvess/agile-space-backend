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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class SupportTicketServiceTest {

    @Mock
    private SupportTicketRepository ticketRepository;

    @Mock
    private SupportTicketReplyRepository replyRepository;

    @Mock
    private NotificationTriggers notificationTriggers;

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

        // outro usuário recebe "não encontrado" (404 no controller), não 403: o id não vaza
        assertThrows(IllegalArgumentException.class, () -> service.getReplies(ticketId, "intruso", false));

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

        assertThrows(IllegalArgumentException.class,
                () -> service.addReply(ticketId, "intruso", "Intruso", false, "quero ver"));

        verify(replyRepository, never()).save(any());
        verify(ticketRepository, never()).save(any());
    }

    // ---------- tipo, protocolo ----------

    @Test
    public void testCreateTicketStoresTypeAndGeneratesProtocolFromSequence() {
        when(ticketRepository.nextProtocolNumber()).thenReturn(42L);
        SupportTicket payload = SupportTicket.builder().subject("Erro").message("Detalhe").type(" BUG ")
                .protocol("SUP-9999").build();

        SupportTicket created = service.createTicket("u1", "Joao", "joao@empresa.com.br", payload);

        assertEquals("bug", created.getType());
        // o protocolo é do servidor; o corpo não escolhe
        assertEquals("SUP-0042", created.getProtocol());
    }

    @Test
    public void testCreateTicketDefaultsTypeToSupport() {
        SupportTicket created = service.createTicket("u1", "Joao", "j@e.com",
                SupportTicket.builder().subject("Erro").message("Detalhe").build());

        assertEquals("support", created.getType());
    }

    @Test
    public void testCreateTicketRejectsUnknownType() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.createTicket("u1", "Joao", "j@e.com",
                SupportTicket.builder().subject("Erro").message("Detalhe").type("reclamacao").build()));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(ticketRepository, never()).save(any());
    }

    @Test
    public void testLegacyTicketWithoutProtocolGetsStableDerivedProtocol() {
        SupportTicket legacy = ticket("OPEN");

        assertEquals("SUP-11111111", legacy.getProtocol());
        assertEquals(legacy.getProtocol(), legacy.getProtocol());
        assertNull(legacy.getType());
    }

    @Test
    public void testStoredProtocolWinsOverDerived() {
        SupportTicket t = ticket("OPEN");
        t.setProtocol("SUP-0007");

        assertEquals("SUP-0007", t.getProtocol());
    }

    // ---------- fechar / reabrir ----------

    @Test
    public void testOwnerClosesOwnTicket() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket("IN_PROGRESS")));

        SupportTicket closed = service.closeTicket(ticketId, "u1");

        assertEquals("CLOSED", closed.getStatus());
        assertNotNull(closed.getUpdatedAt());
    }

    @Test
    public void testCloseByAnotherUserIsNotFound() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket("OPEN")));

        assertThrows(IllegalArgumentException.class, () -> service.closeTicket(ticketId, "intruso"));
        verify(ticketRepository, never()).save(any());
    }

    @Test
    public void testCloseUnknownTicketIsNotFound() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.closeTicket(ticketId, "u1"));
    }

    @Test
    public void testCloseAlreadyClosedIsIdempotent() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket("CLOSED")));

        assertEquals("CLOSED", service.closeTicket(ticketId, "u1").getStatus());
        verify(ticketRepository, never()).save(any());
    }

    @Test
    public void testOwnerReopensClosedTicket() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket("CLOSED")));

        assertEquals("OPEN", service.reopenTicket(ticketId, "u1").getStatus());
    }

    @Test
    public void testReopenByAnotherUserIsNotFound() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket("CLOSED")));

        assertThrows(IllegalArgumentException.class, () -> service.reopenTicket(ticketId, "intruso"));
        verify(ticketRepository, never()).save(any());
    }

    @Test
    public void testReopenOnlyWhenClosed() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket("IN_PROGRESS")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.reopenTicket(ticketId, "u1"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(ticketRepository, never()).save(any());
    }

    @Test
    public void testAdminWhoDoesNotOwnTheTicketCannotUseOwnerActions() {
        // fechar/reabrir/ler são do solicitante; o admin usa PATCH /status
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket("OPEN")));

        assertThrows(IllegalArgumentException.class, () -> service.closeTicket(ticketId, "admin1"));
        assertThrows(IllegalArgumentException.class, () -> service.markRead(ticketId, "admin1"));
    }

    // ---------- responder em chamado fechado ----------

    @Test
    public void testRequesterReplyOnClosedTicketIsConflict() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket("CLOSED")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.addReply(ticketId, "u1", "Joao", false, "ainda com erro"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Reabra"));
        verify(replyRepository, never()).save(any());
    }

    @Test
    public void testAdminCanStillReplyOnClosedTicket() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket("CLOSED")));

        assertTrue(service.addReply(ticketId, "admin1", "Suporte", true, "Fechamos por inatividade").isAdmin());
    }

    // ---------- notificação ao solicitante ----------

    @Test
    public void testAdminReplyNotifiesRequesterWithProtocolAndLink() {
        SupportTicket t = ticket("OPEN");
        t.setProtocol("SUP-0042");
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(t));

        service.addReply(ticketId, "admin1", "Suporte", true, "Já corrigimos");

        verify(notificationTriggers).supportTicketReplied(eq(ticketId), eq("SUP-0042"), eq("Erro no Poker"), eq("u1"),
                anyString(), eq("admin1"));
    }

    @Test
    public void testRequesterReplyDoesNotNotify() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket("OPEN")));

        service.addReply(ticketId, "u1", "Joao", false, "obrigado");

        verifyNoInteractions(notificationTriggers);
    }

    // ---------- leitura ----------

    @Test
    public void testMarkReadStampsTimeClearsUnreadAndRetiresBellNotice() {
        SupportTicket t = ticket("OPEN");
        t.setUnreadReplies(2);
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(t));

        SupportTicket read = service.markRead(ticketId, "u1");

        assertNotNull(read.getRequesterLastReadAt());
        assertEquals(0, read.getUnreadReplies());
        assertFalse(read.isHasUnreadReply());
        verify(notificationTriggers).supportTicketRead(ticketId, "u1");
    }

    @Test
    public void testMarkReadByAnotherUserIsNotFound() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket("OPEN")));

        assertThrows(IllegalArgumentException.class, () -> service.markRead(ticketId, "intruso"));
        verify(ticketRepository, never()).save(any());
        verifyNoInteractions(notificationTriggers);
    }

    @Test
    public void testListMyTicketsFillsUnreadRepliesFromGroupedCount() {
        SupportTicket a = ticket("OPEN");
        SupportTicket b = SupportTicket.builder().id(UUID.fromString("22222222-2222-2222-2222-222222222222"))
                .subject("x").message("y").status("OPEN").requesterId("u1").build();
        when(ticketRepository.findByRequesterIdOrderByCreatedAtDesc("u1")).thenReturn(List.of(a, b));
        when(replyRepository.countUnreadAdminRepliesByRequester("u1"))
                .thenReturn(java.util.Collections.singletonList(new Object[]{ticketId, 3L}));

        List<SupportTicket> mine = service.listMyTickets("u1");

        assertEquals(3, mine.get(0).getUnreadReplies());
        assertTrue(mine.get(0).isHasUnreadReply());
        assertFalse(mine.get(1).isHasUnreadReply());
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
