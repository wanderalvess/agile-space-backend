package com.agilespace.backend.service;

import com.agilespace.backend.domain.SupportTicket;
import com.agilespace.backend.domain.SupportTicketReply;
import com.agilespace.backend.repository.SupportTicketReplyRepository;
import com.agilespace.backend.repository.SupportTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SupportTicketService {

    private final SupportTicketRepository ticketRepository;
    private final SupportTicketReplyRepository replyRepository;
    private final NotificationTriggers notificationTriggers;

    /**
     * Um chamado só pode ser lido/respondido pelo próprio solicitante ou por um ADMIN. Para os demais responde como se
     * não existisse (IllegalArgumentException, que o controller devolve como 404): não revela que o id existe.
     */
    private SupportTicket requireTicketAccess(UUID ticketId, String callerId, boolean isAdmin) {
        SupportTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Support ticket not found with id: " + ticketId));
        if (isAdmin) {
            return ticket;
        }
        return requireOwner(ticket, callerId);
    }

    /** Ações do próprio solicitante (fechar, reabrir, marcar como lido): nem ADMIN que não é o dono. */
    private SupportTicket requireOwnTicket(UUID ticketId, String callerId) {
        SupportTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Support ticket not found with id: " + ticketId));
        return requireOwner(ticket, callerId);
    }

    private static SupportTicket requireOwner(SupportTicket ticket, String callerId) {
        if (callerId == null || !callerId.equals(ticket.getRequesterId())) {
            throw new IllegalArgumentException("Support ticket not found with id: " + ticket.getId());
        }
        return ticket;
    }

    @Transactional(readOnly = true)
    public List<SupportTicket> listMyTickets(String requesterId) {
        List<SupportTicket> mine = ticketRepository.findByRequesterIdOrderByCreatedAtDesc(requesterId);
        if (mine == null || mine.isEmpty()) {
            return mine;
        }
        Map<UUID, Integer> unread = new HashMap<>();
        List<Object[]> rows = replyRepository.countUnreadAdminRepliesByRequester(requesterId);
        if (rows != null) {
            for (Object[] row : rows) {
                unread.put((UUID) row[0], ((Number) row[1]).intValue());
            }
        }
        mine.forEach(t -> t.setUnreadReplies(unread.getOrDefault(t.getId(), 0)));
        return mine;
    }

    @Transactional(readOnly = true)
    public List<SupportTicket> listAllTickets(String statusFilter) {
        if (statusFilter == null || statusFilter.isBlank()) {
            return ticketRepository.findAllByOrderByCreatedAtDesc();
        }
        return ticketRepository.findByStatusOrderByCreatedAtDesc(statusFilter);
    }

    static final int MAX_SUBJECT = 200;
    static final int MAX_MESSAGE = 8000;
    static final int MAX_NAME = 120;
    static final Set<String> STATUSES = Set.of("OPEN", "IN_PROGRESS", "CLOSED");
    static final Set<String> TYPES = Set.of("support", "bug", "suggestion", "feedback");
    static final String DEFAULT_TYPE = "support";

    private static String requireText(String value, int max, String label) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " é obrigatório.");
        }
        if (clean.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " muito longo (máximo " + max + " caracteres).");
        }
        return clean;
    }

    /** Tipo do chamado: ausente = suporte; fora da lista = 400. */
    static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return DEFAULT_TYPE;
        }
        String clean = type.trim().toLowerCase(Locale.ROOT);
        if (!TYPES.contains(clean)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo inválido: use support, bug, suggestion ou feedback.");
        }
        return clean;
    }

    static String formatProtocol(long number) {
        return String.format(Locale.ROOT, "SUP-%04d", number);
    }

    /**
     * Monta o chamado só com os campos permitidos. O objeto do corpo nunca é salvo direto: um id vindo
     * do cliente faria o save atualizar o chamado de outra pessoa e tomar a autoria dele. O protocolo
     * (SUP-NNNN) sai da sequence no servidor; o corpo não escolhe.
     */
    @Transactional
    public SupportTicket createTicket(String requesterId, String requesterName, String requesterEmail, SupportTicket ticket) {
        SupportTicket created = SupportTicket.builder()
                .subject(requireText(ticket.getSubject(), MAX_SUBJECT, "Assunto"))
                .message(requireText(ticket.getMessage(), MAX_MESSAGE, "Mensagem"))
                .type(normalizeType(ticket.getType()))
                .protocol(formatProtocol(ticketRepository.nextProtocolNumber()))
                .requesterId(requesterId)
                .requesterName(requesterName == null ? null : requesterName.trim().substring(0, Math.min(requesterName.trim().length(), MAX_NAME)))
                .requesterEmail(requesterEmail)
                .status("OPEN")
                .updatedAt(LocalDateTime.now())
                .build();
        return ticketRepository.save(created);
    }

    @Transactional
    public SupportTicket updateStatus(UUID ticketId, String newStatus) {
        SupportTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Support ticket not found with id: " + ticketId));
        if (newStatus == null || !STATUSES.contains(newStatus.trim().toUpperCase())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status inválido: use OPEN, IN_PROGRESS ou CLOSED.");
        }

        ticket.setStatus(newStatus.trim().toUpperCase());
        ticket.setUpdatedAt(LocalDateTime.now());
        return ticketRepository.save(ticket);
    }

    /** O solicitante encerra o próprio chamado. Já fechado = devolve como está (idempotente). */
    @Transactional
    public SupportTicket closeTicket(UUID ticketId, String callerId) {
        SupportTicket ticket = requireOwnTicket(ticketId, callerId);
        if ("CLOSED".equals(ticket.getStatus())) {
            return ticket;
        }
        ticket.setStatus("CLOSED");
        ticket.setUpdatedAt(LocalDateTime.now());
        return ticketRepository.save(ticket);
    }

    /** O solicitante reabre o próprio chamado; só faz sentido se estiver fechado (senão 409). */
    @Transactional
    public SupportTicket reopenTicket(UUID ticketId, String callerId) {
        SupportTicket ticket = requireOwnTicket(ticketId, callerId);
        if (!"CLOSED".equals(ticket.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Só é possível reabrir um chamado fechado.");
        }
        ticket.setStatus("OPEN");
        ticket.setUpdatedAt(LocalDateTime.now());
        return ticketRepository.save(ticket);
    }

    /** O solicitante abriu o chamado: zera as respostas não lidas e retira o aviso do sino. */
    @Transactional
    public SupportTicket markRead(UUID ticketId, String callerId) {
        SupportTicket ticket = requireOwnTicket(ticketId, callerId);
        ticket.setRequesterLastReadAt(LocalDateTime.now());
        SupportTicket saved = ticketRepository.save(ticket);
        saved.setUnreadReplies(0);
        notificationTriggers.supportTicketRead(ticketId, callerId);
        return saved;
    }

    @Transactional
    public SupportTicketReply addReply(UUID ticketId, String authorId, String authorName, boolean isAdmin, String message) {
        SupportTicket ticket = requireTicketAccess(ticketId, authorId, isAdmin);
        if (!isAdmin && "CLOSED".equals(ticket.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Este chamado está fechado. Reabra o chamado para enviar uma nova mensagem.");
        }
        String cleanMessage = requireText(message, MAX_MESSAGE, "Mensagem");
        String cleanName = authorName == null || authorName.isBlank() ? null
                : authorName.trim().substring(0, Math.min(authorName.trim().length(), MAX_NAME));

        SupportTicketReply reply = SupportTicketReply.builder()
                .ticketId(ticketId)
                .authorId(authorId)
                .authorName(cleanName)
                .isAdmin(isAdmin)
                .message(cleanMessage)
                .build();
        SupportTicketReply saved = replyRepository.save(reply);

        ticket.setUpdatedAt(LocalDateTime.now());
        ticketRepository.save(ticket);

        if (isAdmin) {
            notificationTriggers.supportTicketReplied(ticketId, ticket.getProtocol(), ticket.getSubject(),
                    ticket.getRequesterId(), saved.getId() == null ? UUID.randomUUID().toString() : saved.getId().toString(),
                    authorId);
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<SupportTicketReply> getReplies(UUID ticketId, String callerId, boolean isAdmin) {
        requireTicketAccess(ticketId, callerId, isAdmin);
        return replyRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
    }

    @Transactional
    public void deleteTicket(UUID ticketId) {
        if (!ticketRepository.existsById(ticketId)) {
            throw new IllegalArgumentException("Support ticket not found with id: " + ticketId);
        }
        replyRepository.deleteByTicketId(ticketId);
        ticketRepository.deleteById(ticketId);
    }
}
