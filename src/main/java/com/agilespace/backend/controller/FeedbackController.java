package com.agilespace.backend.controller;

import com.agilespace.backend.domain.Feedback;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.FeedbackService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Qualquer pessoa logada envia feedback (autoria vem do token, nunca do corpo). Ler, triar e apagar
 * é só do ADMIN: os comentários podem citar pessoas e clientes e não são públicos entre colegas.
 */
@RestController
@RequestMapping("/api/feedbacks")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService service;

    private static void requireAdmin(HttpServletRequest request) {
        String role = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
        if (!"ADMIN".equalsIgnoreCase(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a administradores.");
        }
    }

    @GetMapping
    public ResponseEntity<List<Feedback>> getAllFeedbacks(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(service.getAllFeedbacks());
    }

    @GetMapping(params = "status")
    public ResponseEntity<List<Feedback>> getFeedbacksByStatus(@RequestParam String status, HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(service.getFeedbacksByStatus(status));
    }

    @PostMapping
    public ResponseEntity<Feedback> saveFeedback(@RequestBody Feedback feedback, HttpServletRequest request) {
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida.");
        }
        return ResponseEntity.ok(service.submitFeedback(feedback, userId));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Feedback> updateFeedbackStatus(@PathVariable String id, @RequestParam String status,
                                                         HttpServletRequest request) {
        requireAdmin(request);
        return service.updateFeedbackStatus(id, status)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFeedback(@PathVariable String id, HttpServletRequest request) {
        requireAdmin(request);
        if (!service.deleteFeedback(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }
}
