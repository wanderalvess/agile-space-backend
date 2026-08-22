package com.agilespace.backend.controller;

import com.agilespace.backend.domain.SupportTicket;
import com.agilespace.backend.domain.SupportTicketReply;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.SupportTicketService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/support/tickets")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SupportTicketController {

    private final SupportTicketService supportTicketService;

    @PostMapping
    public ResponseEntity<SupportTicket> createTicket(HttpServletRequest request, @RequestBody SupportTicket ticket) {
        String requesterId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        String requesterEmail = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_EMAIL);
        String requesterName = ticket.getRequesterName();
        if (requesterName == null || requesterName.isBlank()) {
            requesterName = requesterEmail;
        }

        SupportTicket created = supportTicketService.createTicket(requesterId, requesterName, requesterEmail, ticket);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/mine")
    public ResponseEntity<List<SupportTicket>> listMyTickets(HttpServletRequest request) {
        String requesterId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        return ResponseEntity.ok(supportTicketService.listMyTickets(requesterId));
    }

    @GetMapping
    public ResponseEntity<List<SupportTicket>> listAllTickets(@RequestParam(required = false) String status) {
        return ResponseEntity.ok(supportTicketService.listAllTickets(status));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<SupportTicket> updateStatus(@PathVariable UUID id, @RequestParam String status) {
        try {
            return ResponseEntity.ok(supportTicketService.updateStatus(id, status));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id}/replies")
    public ResponseEntity<List<SupportTicketReply>> getReplies(@PathVariable UUID id) {
        return ResponseEntity.ok(supportTicketService.getReplies(id));
    }

    @PostMapping("/{id}/replies")
    public ResponseEntity<SupportTicketReply> addReply(
            HttpServletRequest request,
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        String authorId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        String authorEmail = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_EMAIL);
        String role = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
        boolean isAdmin = "ADMIN".equals(role);
        String authorName = body.getOrDefault("authorName", authorEmail);
        String message = body.get("message");

        try {
            SupportTicketReply reply = supportTicketService.addReply(id, authorId, authorName, isAdmin, message);
            return ResponseEntity.status(HttpStatus.CREATED).body(reply);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTicket(@PathVariable UUID id) {
        try {
            supportTicketService.deleteTicket(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
