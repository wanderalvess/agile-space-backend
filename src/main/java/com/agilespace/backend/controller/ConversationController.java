package com.agilespace.backend.controller;

import com.agilespace.backend.dto.ConversationDto;
import com.agilespace.backend.dto.MessageDto;
import com.agilespace.backend.dto.UserRefDto;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.ConversationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/** Mensagens diretas (docs/home-api.md §3). Quem chama vem sempre do JWT. */
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    public record OpenConversationRequest(String userId) { }

    public record SendMessageRequest(String body) { }

    @GetMapping
    public ResponseEntity<List<ConversationDto>> list(HttpServletRequest request) {
        return ResponseEntity.ok(conversationService.list(userId(request)));
    }

    @GetMapping("/contacts")
    public ResponseEntity<List<UserRefDto>> contacts(HttpServletRequest request) {
        return ResponseEntity.ok(conversationService.contacts(userId(request), role(request)));
    }

    @PostMapping
    public ResponseEntity<ConversationDto> open(@RequestBody(required = false) OpenConversationRequest body,
                                                HttpServletRequest request) {
        return ResponseEntity.ok(conversationService.open(userId(request), role(request), body == null ? null : body.userId()));
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<List<MessageDto>> messages(@PathVariable String id,
                                                     @RequestParam(required = false) Integer limit,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
                                                     HttpServletRequest request) {
        return ResponseEntity.ok(conversationService.messages(userId(request), id, limit, before));
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<MessageDto> send(@PathVariable String id, @RequestBody(required = false) SendMessageRequest body,
                                           HttpServletRequest request) {
        return ResponseEntity.ok(conversationService.send(userId(request), id, body == null ? null : body.body()));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(@PathVariable String id, HttpServletRequest request) {
        conversationService.markRead(userId(request), id);
        return ResponseEntity.noContent().build();
    }

    private static String userId(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
    }

    private static String role(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
    }
}
