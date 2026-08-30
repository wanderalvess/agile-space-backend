package com.agilespace.backend.controller;

import com.agilespace.backend.domain.UserFocusSession;
import com.agilespace.backend.repository.UserFocusSessionRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/focus-sessions")
public class UserFocusSessionController {

    @Autowired
    private UserFocusSessionRepository repository;

    /**
     * Sessão de foco é dado pessoal: só o próprio usuário (ou um ADMIN) lê e grava.
     * Antes disso qualquer usuário autenticado lia as sessões de outro trocando o userId na URL.
     */
    private static void requireSelfOrAdmin(String userId, HttpServletRequest request) {
        String callerId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        String role = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
        if ("ADMIN".equalsIgnoreCase(role)) {
            return;
        }
        if (callerId == null || !callerId.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito ao próprio usuário.");
        }
    }

    @GetMapping("/{userId}")
    public ResponseEntity<List<UserFocusSession>> getSessions(@PathVariable String userId, HttpServletRequest request) {
        requireSelfOrAdmin(userId, request);
        return ResponseEntity.ok(repository.findByUserIdOrderByCreatedAtDesc(userId));
    }

    @PostMapping("/{userId}")
    public ResponseEntity<UserFocusSession> saveSession(@PathVariable String userId, @RequestBody UserFocusSession session, HttpServletRequest request) {
        requireSelfOrAdmin(userId, request);
        session.setUserId(userId);
        if (session.getId() == null || session.getId().isEmpty()) {
            session.setId(UUID.randomUUID().toString());
        }
        session.setCreatedAt(LocalDateTime.now());
        UserFocusSession saved = repository.save(session);
        return ResponseEntity.ok(saved);
    }
}
