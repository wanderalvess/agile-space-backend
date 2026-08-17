package com.agilespace.backend.service;

import com.agilespace.backend.domain.Prompt;
import com.agilespace.backend.domain.PromptComment;
import com.agilespace.backend.repository.PromptCommentRepository;
import com.agilespace.backend.repository.PromptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PromptService {

    private final PromptRepository promptRepository;
    private final PromptCommentRepository commentRepository;

    @Transactional(readOnly = true)
    public Page<Prompt> listPrompts(String query, String authorId, Pageable pageable) {
        if (query != null && !query.trim().isEmpty()) {
            return promptRepository.searchPublic(query, "public", pageable);
        }
        if (authorId != null && !authorId.trim().isEmpty()) {
            return promptRepository.findByAuthorId(authorId, pageable);
        }
        return promptRepository.findByVisibility("public", pageable);
    }

    @Transactional(readOnly = true)
    public Prompt getPromptById(UUID id) {
        return promptRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Prompt not found with id: " + id));
    }

    @Transactional
    public Prompt createPrompt(Prompt prompt) {
        // Reset counters for new prompt
        prompt.setUseCount(0);
        prompt.setForkCount(0);
        return promptRepository.save(prompt);
    }

    @Transactional
    public Prompt updatePrompt(UUID id, Prompt updatedPrompt) {
        Prompt existing = getPromptById(id);
        existing.setTitle(updatedPrompt.getTitle());
        existing.setDescription(updatedPrompt.getDescription());
        existing.setContent(updatedPrompt.getContent());
        existing.setType(updatedPrompt.getType());
        existing.setVisibility(updatedPrompt.getVisibility());
        existing.setStatus(updatedPrompt.getStatus());
        existing.setImpact(updatedPrompt.getImpact());
        existing.setBusinessGoal(updatedPrompt.getBusinessGoal());
        existing.setTargetAudience(updatedPrompt.getTargetAudience());
        existing.setGemLink(updatedPrompt.getGemLink());
        existing.setArchitectureLink(updatedPrompt.getArchitectureLink());
        existing.setTags(updatedPrompt.getTags());
        return promptRepository.save(existing);
    }

    @Transactional
    public void deletePrompt(UUID id) {
        // Comments will be deleted automatically or manually
        List<PromptComment> comments = commentRepository.findByPromptIdOrderByCreatedAtAsc(id);
        commentRepository.deleteAll(comments);
        promptRepository.deleteById(id);
    }

    @Transactional
    public Prompt incrementUseCount(UUID id) {
        Prompt prompt = getPromptById(id);
        prompt.setUseCount(prompt.getUseCount() + 1);
        return promptRepository.save(prompt);
    }

    @Transactional
    public Prompt incrementForkCount(UUID id) {
        Prompt prompt = getPromptById(id);
        prompt.setForkCount(prompt.getForkCount() + 1);
        return promptRepository.save(prompt);
    }

    // Comment operations
    @Transactional
    public PromptComment addComment(UUID promptId, PromptComment comment) {
        Prompt prompt = getPromptById(promptId);
        comment.setPrompt(prompt);
        return commentRepository.save(comment);
    }

    @Transactional(readOnly = true)
    public List<PromptComment> getComments(UUID promptId) {
        return commentRepository.findByPromptIdOrderByCreatedAtAsc(promptId);
    }
}
