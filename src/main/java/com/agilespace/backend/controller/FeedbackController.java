package com.agilespace.backend.controller;

import com.agilespace.backend.domain.Feedback;
import com.agilespace.backend.repository.FeedbackRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/feedbacks")
public class FeedbackController {

    @Autowired
    private FeedbackRepository repository;

    @GetMapping
    public ResponseEntity<List<Feedback>> getAllFeedbacks() {
        return ResponseEntity.ok(repository.findByOrderByCreatedAtDesc());
    }

    @PostMapping
    public ResponseEntity<Feedback> saveFeedback(@RequestBody Feedback feedback) {
        if (feedback.getId() == null || feedback.getId().isEmpty()) {
            feedback.setId(UUID.randomUUID().toString());
        }
        feedback.setCreatedAt(LocalDateTime.now());
        Feedback saved = repository.save(feedback);
        return ResponseEntity.ok(saved);
    }
}
