package com.agilespace.backend.service;

import com.agilespace.backend.domain.Feedback;
import com.agilespace.backend.repository.FeedbackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
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

    static final int MAX_COMMENT = 4000;
    static final int MAX_TOOL_NAME = 80;
    static final java.util.Set<String> STATUSES = java.util.Set.of("OPEN", "REVIEWED", "ARCHIVED");

    /**
     * Entrada vinda do cliente: o id é sempre novo (um id existente no corpo sobrescreveria o feedback de
     * outra pessoa), a autoria vem do token e o status começa em OPEN. Nota -1 = sugestão sem nota.
     */
    @Transactional
    public Feedback submitFeedback(Feedback incoming, String userId) {
        String toolName = incoming.getToolName() == null ? "" : incoming.getToolName().trim();
        if (toolName.isEmpty() || toolName.length() > MAX_TOOL_NAME) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe a ferramenta avaliada (até " + MAX_TOOL_NAME + " caracteres).");
        }
        Integer score = incoming.getScore();
        if (score != null && (score < -1 || score > 10)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nota inválida: use de 0 a 10.");
        }
        String comment = incoming.getComment() == null ? null : incoming.getComment().trim();
        if (comment != null && comment.length() > MAX_COMMENT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comentário muito longo (máximo " + MAX_COMMENT + " caracteres).");
        }
        Feedback feedback = Feedback.builder()
                .id(UUID.randomUUID().toString())
                .toolName(toolName)
                .score(score)
                .comment(comment)
                .userId(userId)
                .status("OPEN")
                .build();
        return saveFeedback(feedback);
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
        if (status == null || !STATUSES.contains(status.trim().toUpperCase())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status inválido: use OPEN, REVIEWED ou ARCHIVED.");
        }
        status = status.trim().toUpperCase();
        final String newStatus = status;
        return repository.findById(id).map(feedback -> {
            feedback.setStatus(newStatus);
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
