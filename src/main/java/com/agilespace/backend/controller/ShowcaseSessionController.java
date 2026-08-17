package com.agilespace.backend.controller;

import com.agilespace.backend.domain.ShowcaseSession;
import com.agilespace.backend.service.ShowcaseSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/showcase-sessions")
public class ShowcaseSessionController {

    @Autowired
    private ShowcaseSessionService service;

    @GetMapping
    public ResponseEntity<List<ShowcaseSession>> getSessions(@RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(service.getLatestSessions(limit));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ShowcaseSession> getSession(@PathVariable String id) {
        ShowcaseSession session = service.getSession(id);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(session);
    }

    @PostMapping
    public ResponseEntity<ShowcaseSession> saveSession(@RequestBody ShowcaseSession session) {
        ShowcaseSession saved = service.saveSession(session);
        return ResponseEntity.ok(saved);
    }
}
