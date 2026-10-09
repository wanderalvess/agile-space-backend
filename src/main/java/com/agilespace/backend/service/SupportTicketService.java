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
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SupportTicketService {

    private final SupportTicketRepository ticketRepository;
    private final SupportTicketReplyRepository replyRepository;

    /**
     * Um chamado só pode ser lido/respondido pelo próprio solicitante ou por um ADMIN.
     * Antes disso qualquer usuário autenticado lia e respondia chamado alheio adivinhando o id.
     */
    private SupportTicket requireTicketAccess(UUID ticketId, String callerId, boolean isAdmin) {
        SupportTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Support ticket not found with id: " + ticketId));
        if (isAdmin) {
            return ticket;
        }
        if (callerId == null || !callerId.equals(ticket.getRequesterId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito ao solicitante do chamado ou a administradores.");
        }
        return ticket;
    }

    @Transactional(readOnly = true)
    public List<SupportTicket> listMyTickets(String requesterId) {
        return ticketRepository.findByRequesterIdOrderByCreatedAtDesc(requesterId);
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

    /**
     * Monta o chamado só com os campos permitidos. O objeto do corpo nunca é salvo direto: um id vindo
     * do cliente faria o save atualizar o chamado de outra pessoa e tomar a autoria dele.
     */
    @Transactional
    public SupportTicket createTicket(String requesterId, String requesterName, String requesterEmail, SupportTicket ticket) {
        SupportTicket created = SupportTicket.builder()
                .subject(requireText(ticket.getSubject(), MAX_SUBJECT, "Assunto"))
                .message(requireText(ticket.getMessage(), MAX_MESSAGE, "Mensagem"))
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

    @Transactional
    public SupportTicketReply addReply(UUID ticketId, String authorId, String authorName, boolean isAdmin, String message) {
        SupportTicket ticket = requireTicketAccess(ticketId, authorId, isAdmin);
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
