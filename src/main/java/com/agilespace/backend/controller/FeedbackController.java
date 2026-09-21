package com.agilespace.backend.controller;

import com.agilespace.backend.domain.Feedback;
import com.agilespace.backend.service.FeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/feedbacks")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService service;

    @GetMapping
    public ResponseEntity<List<Feedback>> getAllFeedbacks() {
        return ResponseEntity.ok(service.getAllFeedbacks());
    }

    @GetMapping(params = "status")
    public ResponseEntity<List<Feedback>> getFeedbacksByStatus(@RequestParam String status) {
        return ResponseEntity.ok(service.getFeedbacksByStatus(status));
    }

    @PostMapping
    public ResponseEntity<Feedback> saveFeedback(@RequestBody Feedback feedback) {
        return ResponseEntity.ok(service.saveFeedback(feedback));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Feedback> updateFeedbackStatus(@PathVariable String id, @RequestParam String status) {
        return service.updateFeedbackStatus(id, status)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFeedback(@PathVariable String id) {
        if (!service.deleteFeedback(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }
}
