package com.agilespace.backend.service;

import com.agilespace.backend.domain.Prompt;
import com.agilespace.backend.domain.PromptComment;
import com.agilespace.backend.domain.PromptCollection;
import com.agilespace.backend.repository.PromptCommentRepository;
import com.agilespace.backend.repository.PromptCollectionRepository;
import com.agilespace.backend.repository.PromptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PromptService {

    private final PromptRepository promptRepository;
    private final PromptCommentRepository commentRepository;
    private final PromptCollectionRepository promptCollectionRepository;

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

    /**
     * Mesma listagem, mas SEMPRE restrita a visibility="public" — inclusive no filtro
     * por authorId (listPrompts acima não faz essa restrição ali, então não serve pra
     * chamador externo/API key: um authorId vazaria os prompts privados desse autor).
     * Usado pelo Prompt Hub exposto a máquina/serviço (PromptHubApiV1Controller, MCP).
     */
    @Transactional(readOnly = true)
    public Page<Prompt> listPublicPrompts(String query, String authorId, Pageable pageable) {
        if (query != null && !query.trim().isEmpty()) {
            return promptRepository.searchPublic(query, "public", pageable);
        }
        if (authorId != null && !authorId.trim().isEmpty()) {
            return promptRepository.findByAuthorIdAndVisibility(authorId, "public", pageable);
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
    public Prompt saveOrUpdateSkill(Prompt skillPrompt) {
        if (skillPrompt.getType() == null) {
            skillPrompt.setType("skill");
        }
        // Upsert só considera skill do mesmo autor — sem fallback por título global, pra
        // uma API key não sobrescrever skill de outro usuário só por colisão de nome.
        Optional<Prompt> existingOpt = Optional.empty();
        if (skillPrompt.getAuthorId() != null && skillPrompt.getTitle() != null) {
            existingOpt = promptRepository.findFirstByAuthorIdAndTitleAndType(
                    skillPrompt.getAuthorId(), skillPrompt.getTitle(), "skill");
        }

        if (existingOpt.isPresent()) {
            Prompt existing = existingOpt.get();
            existing.setContent(skillPrompt.getContent());
            if (skillPrompt.getDescription() != null && !skillPrompt.getDescription().isBlank()) {
                existing.setDescription(skillPrompt.getDescription());
            }
            if (skillPrompt.getTags() != null && !skillPrompt.getTags().isEmpty()) {
                existing.setTags(skillPrompt.getTags());
            }
            if (skillPrompt.getVisibility() != null) {
                existing.setVisibility(skillPrompt.getVisibility());
            }
            if (skillPrompt.getStatus() != null) {
                existing.setStatus(skillPrompt.getStatus());
            }
            return promptRepository.save(existing);
        }

        return createPrompt(skillPrompt);
    }

    @Transactional
    public List<Prompt> createPromptsBatch(List<Prompt> prompts) {
        return prompts.stream()
                .map(p -> {
                    if ("skill".equalsIgnoreCase(p.getType())) {
                        return saveOrUpdateSkill(p);
                    } else {
                        return createPrompt(p);
                    }
                })
                .collect(Collectors.toList());
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

    // Coleções
    @Transactional(readOnly = true)
    public Page<PromptCollection> listCollections(String visibility, String ownerId, Pageable pageable) {
        boolean hasOwnerId = ownerId != null && !ownerId.trim().isEmpty();
        boolean hasVisibility = visibility != null && !visibility.trim().isEmpty();

        if (hasOwnerId && hasVisibility) {
            Page<PromptCollection> ownerPage = promptCollectionRepository.findByOwnerId(ownerId, pageable);
            List<PromptCollection> filtered = ownerPage.getContent().stream()
                    .filter(c -> visibility.equals(c.getVisibility()))
                    .collect(Collectors.toList());
            return new PageImpl<>(filtered, pageable, filtered.size());
        }
        if (hasOwnerId) {
            return promptCollectionRepository.findByOwnerId(ownerId, pageable);
        }
        if (hasVisibility) {
            return promptCollectionRepository.findByVisibility(visibility, pageable);
        }
        return promptCollectionRepository.findByVisibility("public", pageable);
    }

    @Transactional(readOnly = true)
    public PromptCollection getCollectionById(UUID id) {
        return promptCollectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Collection not found with id: " + id));
    }

    @Transactional
    public PromptCollection createCollection(PromptCollection collection) {
        return promptCollectionRepository.save(collection);
    }

    @Transactional
    public PromptCollection updateCollection(UUID id, PromptCollection updatedCollection) {
        PromptCollection existing = getCollectionById(id);
        existing.setName(updatedCollection.getName());
        existing.setDescription(updatedCollection.getDescription());
        existing.setVisibility(updatedCollection.getVisibility());
        if (updatedCollection.getItems() != null) {
            List<Prompt> resolvedItems = updatedCollection.getItems().stream()
                    .map(item -> getPromptById(item.getId()))
                    .collect(Collectors.toList());
            existing.setItems(resolvedItems);
        }
        return promptCollectionRepository.save(existing);
    }

    @Transactional
    public void deleteCollection(UUID id) {
        PromptCollection existing = getCollectionById(id);
        promptCollectionRepository.delete(existing);
    }

    @Transactional
    public PromptCollection addItemToCollection(UUID collectionId, UUID promptId) {
        PromptCollection collection = getCollectionById(collectionId);
        Prompt prompt = getPromptById(promptId);
        boolean alreadyPresent = collection.getItems().stream()
                .anyMatch(p -> p.getId().equals(promptId));
        if (!alreadyPresent) {
            collection.getItems().add(prompt);
        }
        return promptCollectionRepository.save(collection);
    }

    @Transactional
    public PromptCollection removeItemFromCollection(UUID collectionId, UUID promptId) {
        PromptCollection collection = getCollectionById(collectionId);
        collection.getItems().removeIf(p -> p.getId().equals(promptId));
        return promptCollectionRepository.save(collection);
    }
}
