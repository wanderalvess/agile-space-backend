package com.agilespace.backend.service;

import com.agilespace.backend.domain.Feedback;
import com.agilespace.backend.repository.FeedbackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final FeedbackRepository repository;

    @Transactional(readOnly = true)
    public List<Feedback> getAllFeedbacks() {
        return repository.findByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<Feedback> getFeedbacksByStatus(String status) {
        return repository.findByStatus(status);
    }

    @Transactional
    public Feedback saveFeedback(Feedback feedback) {
        if (feedback.getId() == null || feedback.getId().isEmpty()) {
            feedback.setId(UUID.randomUUID().toString());
        }
        feedback.setCreatedAt(LocalDateTime.now());
        return repository.save(feedback);
    }

    @Transactional
    public Optional<Feedback> updateFeedbackStatus(String id, String status) {
        return repository.findById(id).map(feedback -> {
            feedback.setStatus(status);
            return repository.save(feedback);
        });
    }

    @Transactional
    public boolean deleteFeedback(String id) {
        if (!repository.existsById(id)) {
            return false;
        }
        repository.deleteById(id);
        return true;
    }
}
