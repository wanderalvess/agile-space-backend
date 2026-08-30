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
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/support/tickets")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SupportTicketController {

    private final SupportTicketService supportTicketService;

    private static boolean isAdmin(HttpServletRequest request) {
        return "ADMIN".equals(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE));
    }

    /**
     * Triagem do suporte (listar todos, mudar status, apagar) é exclusiva de ADMIN.
     * Antes disso qualquer usuário autenticado listava e apagava chamado de terceiros.
     */
    private static void requireAdmin(HttpServletRequest request) {
        if (!isAdmin(request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a administradores.");
        }
    }

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
    public ResponseEntity<List<SupportTicket>> listAllTickets(
            HttpServletRequest request,
            @RequestParam(required = false) String status) {
        requireAdmin(request);
        return ResponseEntity.ok(supportTicketService.listAllTickets(status));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<SupportTicket> updateStatus(
            HttpServletRequest request,
            @PathVariable UUID id,
            @RequestParam String status) {
        requireAdmin(request);
        try {
            return ResponseEntity.ok(supportTicketService.updateStatus(id, status));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id}/replies")
    public ResponseEntity<List<SupportTicketReply>> getReplies(HttpServletRequest request, @PathVariable UUID id) {
        String callerId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        try {
            return ResponseEntity.ok(supportTicketService.getReplies(id, callerId, isAdmin(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/replies")
    public ResponseEntity<SupportTicketReply> addReply(
            HttpServletRequest request,
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        String authorId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        String authorEmail = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_EMAIL);
        boolean callerIsAdmin = isAdmin(request);
        String authorName = body.getOrDefault("authorName", authorEmail);
        String message = body.get("message");

        try {
            SupportTicketReply reply = supportTicketService.addReply(id, authorId, authorName, callerIsAdmin, message);
            return ResponseEntity.status(HttpStatus.CREATED).body(reply);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTicket(HttpServletRequest request, @PathVariable UUID id) {
        requireAdmin(request);
        try {
            supportTicketService.deleteTicket(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
