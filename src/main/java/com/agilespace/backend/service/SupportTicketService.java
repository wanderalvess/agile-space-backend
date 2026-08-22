package com.agilespace.backend.service;

import com.agilespace.backend.domain.SupportTicket;
import com.agilespace.backend.domain.SupportTicketReply;
import com.agilespace.backend.repository.SupportTicketReplyRepository;
import com.agilespace.backend.repository.SupportTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SupportTicketService {

    private final SupportTicketRepository ticketRepository;
    private final SupportTicketReplyRepository replyRepository;

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

    @Transactional
    public SupportTicket createTicket(String requesterId, String requesterName, String requesterEmail, SupportTicket ticket) {
        ticket.setRequesterId(requesterId);
        ticket.setRequesterName(requesterName);
        ticket.setRequesterEmail(requesterEmail);
        ticket.setStatus("OPEN");
        ticket.setUpdatedAt(LocalDateTime.now());
        return ticketRepository.save(ticket);
    }

    @Transactional
    public SupportTicket updateStatus(UUID ticketId, String newStatus) {
        SupportTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Support ticket not found with id: " + ticketId));

        ticket.setStatus(newStatus);
        ticket.setUpdatedAt(LocalDateTime.now());
        return ticketRepository.save(ticket);
    }

    @Transactional
    public SupportTicketReply addReply(UUID ticketId, String authorId, String authorName, boolean isAdmin, String message) {
        SupportTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Support ticket not found with id: " + ticketId));

        SupportTicketReply reply = SupportTicketReply.builder()
                .ticketId(ticketId)
                .authorId(authorId)
                .authorName(authorName)
                .isAdmin(isAdmin)
                .message(message)
                .build();
        SupportTicketReply saved = replyRepository.save(reply);

        ticket.setUpdatedAt(LocalDateTime.now());
        ticketRepository.save(ticket);

        return saved;
    }

    @Transactional(readOnly = true)
    public List<SupportTicketReply> getReplies(UUID ticketId) {
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
