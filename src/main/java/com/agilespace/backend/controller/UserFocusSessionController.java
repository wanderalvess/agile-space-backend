package com.agilespace.backend.controller;

import com.agilespace.backend.domain.UserFocusSession;
import com.agilespace.backend.repository.UserFocusSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/focus-sessions")
public class UserFocusSessionController {

    @Autowired
    private UserFocusSessionRepository repository;

    @GetMapping("/{userId}")
    public ResponseEntity<List<UserFocusSession>> getSessions(@PathVariable String userId) {
        return ResponseEntity.ok(repository.findByUserIdOrderByCreatedAtDesc(userId));
    }

    @PostMapping("/{userId}")
    public ResponseEntity<UserFocusSession> saveSession(@PathVariable String userId, @RequestBody UserFocusSession session) {
        session.setUserId(userId);
        if (session.getId() == null || session.getId().isEmpty()) {
            session.setId(UUID.randomUUID().toString());
        }
        session.setCreatedAt(LocalDateTime.now());
        UserFocusSession saved = repository.save(session);
        return ResponseEntity.ok(saved);
    }
}
