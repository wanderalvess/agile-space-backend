package com.agilespace.backend.controller;

import com.agilespace.backend.dto.JiraPublishDtos;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.JiraPublishService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Gestão da fila de publicação no Jira fora da sala do Poker: todos os envios da squad, log de cada um,
 * retentativa, descarte e conferência no Jira. O acesso é de membro da squad; as regras finas ficam no serviço.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class JiraPublishQueueController {

    private final JiraPublishService service;

    @GetMapping("/squads/{squadId}/jira-publish")
    public ResponseEntity<JiraPublishDtos.SquadQueue> queue(@PathVariable String squadId, HttpServletRequest request) {
        return ResponseEntity.ok(service.listForSquad(squadId, userId(request), role(request)));
    }

    @GetMapping("/jira-publish/jobs/{jobId}/events")
    public ResponseEntity<List<JiraPublishDtos.Event>> events(@PathVariable String jobId, HttpServletRequest request) {
        return ResponseEntity.ok(service.listEvents(jobId, userId(request), role(request)));
    }

    @PostMapping("/jira-publish/jobs/{jobId}/retry")
    public ResponseEntity<JiraPublishDtos.Job> retry(@PathVariable String jobId, HttpServletRequest request) {
        return ResponseEntity.ok(service.retryJob(jobId, userId(request), role(request)));
    }

    @PostMapping("/jira-publish/jobs/{jobId}/verify")
    public ResponseEntity<JiraPublishDtos.VerifyResult> verify(@PathVariable String jobId, HttpServletRequest request) {
        return ResponseEntity.ok(service.verify(jobId, userId(request), role(request)));
    }

    @DeleteMapping("/jira-publish/jobs/{jobId}")
    public ResponseEntity<Void> discard(@PathVariable String jobId, HttpServletRequest request) {
        service.discard(jobId, userId(request), role(request));
        return ResponseEntity.noContent().build();
    }

    private static String userId(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
    }

    private static String role(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
    }

    // Em produção o Spring oculta a razão das ResponseStatusException (include-message: never).
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "error", String.valueOf(ex.getStatusCode().value()),
                "message", ex.getReason() == null ? "" : ex.getReason()
        ));
    }
}
