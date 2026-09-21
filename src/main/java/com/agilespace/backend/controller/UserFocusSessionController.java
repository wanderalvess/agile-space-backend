package com.agilespace.backend.controller;

import com.agilespace.backend.domain.UserFocusSession;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.UserFocusSessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/focus-sessions")
@RequiredArgsConstructor
public class UserFocusSessionController {

    private final UserFocusSessionService service;

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
        return ResponseEntity.ok(service.getSessions(userId));
    }

    @PostMapping("/{userId}")
    public ResponseEntity<UserFocusSession> saveSession(@PathVariable String userId, @RequestBody UserFocusSession session, HttpServletRequest request) {
        requireSelfOrAdmin(userId, request);
        return ResponseEntity.ok(service.saveSession(userId, session));
    }
}
