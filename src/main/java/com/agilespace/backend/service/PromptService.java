package com.agilespace.backend.service;

import com.agilespace.backend.domain.Prompt;
import com.agilespace.backend.domain.PromptComment;
import com.agilespace.backend.domain.PromptCollection;
import com.agilespace.backend.repository.PromptCommentRepository;
import com.agilespace.backend.repository.PromptCollectionRepository;
import com.agilespace.backend.repository.PromptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PromptService {

    private static final String PUBLIC = "public";
    private static final String PRIVATE = "private";
    private static final int MAX_PAGE_SIZE = 200;

    private final PromptRepository promptRepository;
    private final PromptCommentRepository commentRepository;
    private final PromptCollectionRepository promptCollectionRepository;

    // ───────────── Regras de acesso ─────────────

    /** Itens públicos são de todos; os demais, só do autor (ou de ADMIN). */
    private boolean canView(Prompt prompt, PromptCaller caller) {
        if (PUBLIC.equals(prompt.getVisibility())) return true;
        return caller != null && (caller.isAdmin() || caller.is(prompt.getAuthorId()));
    }

    private boolean canManage(String ownerId, PromptCaller caller) {
        return caller != null && (caller.isAdmin() || caller.is(ownerId));
    }

    private void requireManage(String ownerId, PromptCaller caller) {
        if (!canManage(ownerId, caller)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Você não tem permissão para alterar este item");
        }
    }

    private static String normalizeVisibility(String visibility) {
        return PUBLIC.equals(visibility) ? PUBLIC : PRIVATE;
    }

    /** Sem ordenação explícita a paginação do Postgres é arbitrária: padroniza por atualização mais recente. */
    private static Pageable sorted(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) return pageable;
        int size = Math.min(pageable.getPageSize(), MAX_PAGE_SIZE);
        Sort sort = pageable.getSort().isSorted() ? pageable.getSort() : Sort.by(Sort.Direction.DESC, "updatedAt");
        return PageRequest.of(pageable.getPageNumber(), size, sort);
    }

    private static Set<String> cleanTags(Set<String> tags) {
        if (tags == null) return new java.util.HashSet<>();
        return tags.stream()
                .filter(t -> t != null)
                .map(t -> t.trim().replace("#", "").toLowerCase())
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Dados que o cliente não pode escolher: id, autoria, contadores e datas. */
    private Prompt prepareForCreate(Prompt prompt, PromptCaller caller) {
        prompt.setId(null);
        prompt.setCreatedAt(null);
        prompt.setUpdatedAt(null);
        prompt.setAuthorId(caller.id());
        prompt.setVisibility(normalizeVisibility(prompt.getVisibility()));
        prompt.setTags(cleanTags(prompt.getTags()));
        if (prompt.getTitle() != null) prompt.setTitle(prompt.getTitle().trim());
        return prompt;
    }

    // ───────────── Prompts ─────────────

    /**
     * Listagem do app. Sem authorId: só públicos. Com authorId: todos os itens do autor para
     * ele mesmo (ou ADMIN), apenas os públicos para os demais.
     */
    @Transactional(readOnly = true)
    public Page<Prompt> listPrompts(String query, String authorId, PromptCaller caller, Pageable pageable) {
        Pageable p = sorted(pageable);
        if (query != null && !query.trim().isEmpty()) {
            return promptRepository.searchPublic(query.trim(), PUBLIC, p);
        }
        if (authorId != null && !authorId.trim().isEmpty()) {
            return canManage(authorId, caller)
                    ? promptRepository.findByAuthorId(authorId, p)
                    : promptRepository.findByAuthorIdAndVisibility(authorId, PUBLIC, p);
        }
        return promptRepository.findByVisibility(PUBLIC, p);
    }

    /**
     * Mesma listagem, mas SEMPRE restrita a visibility="public" — inclusive no filtro
     * por authorId. Usado pelo Prompt Hub exposto a máquina/serviço (PromptHubApiV1Controller, MCP).
     */
    @Transactional(readOnly = true)
    public Page<Prompt> listPublicPrompts(String query, String authorId, Pageable pageable) {
        Pageable p = sorted(pageable);
        if (query != null && !query.trim().isEmpty()) {
            return promptRepository.searchPublic(query, PUBLIC, p);
        }
        if (authorId != null && !authorId.trim().isEmpty()) {
            return promptRepository.findByAuthorIdAndVisibility(authorId, PUBLIC, p);
        }
        return promptRepository.findByVisibility(PUBLIC, p);
    }

    /** Sem checagem de acesso: uso interno (API key / MCP já filtram visibilidade). */
    @Transactional(readOnly = true)
    public Prompt getPromptById(UUID id) {
        return promptRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Prompt not found with id: " + id));
    }

    /** Item que o chamador não enxerga é tratado como inexistente (404), sem confirmar que existe. */
    @Transactional(readOnly = true)
    public Prompt getVisiblePrompt(UUID id, PromptCaller caller) {
        Prompt prompt = getPromptById(id);
        if (!canView(prompt, caller)) {
            throw new IllegalArgumentException("Prompt not found with id: " + id);
        }
        return prompt;
    }

    /** Criação sem identidade (API key / MCP definem a autoria por conta própria). */
    @Transactional
    public Prompt createPrompt(Prompt prompt) {
        // Reset counters for new prompt
        prompt.setUseCount(0);
        prompt.setForkCount(0);
        return promptRepository.save(prompt);
    }

    @Transactional
    public Prompt createPrompt(Prompt prompt, PromptCaller caller) {
        return createPrompt(prepareForCreate(prompt, caller));
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

    /**
     * Upsert vindo do app: autoria sempre do chamador. Ao atualizar uma skill já existente, a
     * visibilidade dela é preservada (reimportar não pode tornar pública uma skill privada).
     */
    @Transactional
    public Prompt saveOrUpdateSkill(Prompt skillPrompt, PromptCaller caller) {
        prepareForCreate(skillPrompt, caller);
        Optional<Prompt> existing = skillPrompt.getTitle() == null ? Optional.empty()
                : promptRepository.findFirstByAuthorIdAndTitleAndType(caller.id(), skillPrompt.getTitle(), "skill");
        if (existing.isPresent()) {
            skillPrompt.setVisibility(existing.get().getVisibility());
        }
        return saveOrUpdateSkill(skillPrompt);
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
    public List<Prompt> createPromptsBatch(List<Prompt> prompts, PromptCaller caller) {
        return prompts.stream()
                .map(p -> "skill".equalsIgnoreCase(p.getType())
                        ? saveOrUpdateSkill(p, caller)
                        : createPrompt(p, caller))
                .collect(Collectors.toList());
    }

    @Transactional
    public Prompt updatePrompt(UUID id, Prompt updatedPrompt, PromptCaller caller) {
        Prompt existing = getVisiblePrompt(id, caller);
        requireManage(existing.getAuthorId(), caller);
        existing.setTitle(updatedPrompt.getTitle() == null ? null : updatedPrompt.getTitle().trim());
        existing.setDescription(updatedPrompt.getDescription());
        existing.setContent(updatedPrompt.getContent());
        existing.setType(updatedPrompt.getType());
        existing.setVisibility(normalizeVisibility(updatedPrompt.getVisibility()));
        existing.setStatus(updatedPrompt.getStatus());
        existing.setImpact(updatedPrompt.getImpact());
        existing.setBusinessGoal(updatedPrompt.getBusinessGoal());
        existing.setTargetAudience(updatedPrompt.getTargetAudience());
        existing.setGemLink(updatedPrompt.getGemLink());
        existing.setArchitectureLink(updatedPrompt.getArchitectureLink());
        existing.setTags(cleanTags(updatedPrompt.getTags()));
        return promptRepository.save(existing);
    }

    @Transactional
    public void deletePrompt(UUID id, PromptCaller caller) {
        Prompt existing = getVisiblePrompt(id, caller);
        requireManage(existing.getAuthorId(), caller);

        // prompt_collection_items tem FK para o prompt: sem tirar o item das coleções a exclusão falha.
        for (PromptCollection collection : promptCollectionRepository.findDistinctByItemsId(id)) {
            collection.getItems().removeIf(p -> p.getId().equals(id));
            promptCollectionRepository.save(collection);
        }
        commentRepository.deleteAll(commentRepository.findByPromptId(id));
        promptRepository.delete(existing);
    }

    @Transactional
    public Prompt incrementUseCount(UUID id, PromptCaller caller) {
        getVisiblePrompt(id, caller);
        promptRepository.incrementUseCount(id);
        return getPromptById(id);
    }

    @Transactional
    public Prompt incrementForkCount(UUID id, PromptCaller caller) {
        getVisiblePrompt(id, caller);
        promptRepository.incrementForkCount(id);
        return getPromptById(id);
    }

    /**
     * Duplica o item para a biblioteca privada do chamador e conta o clone na origem, na mesma
     * transação (antes eram duas chamadas do cliente e uma falha deixava cópia sem contagem).
     * {@code snapshot} só empresta a autoria denormalizada (nome, cargo, squad, avatar).
     */
    @Transactional
    public Prompt clonePrompt(UUID id, Prompt snapshot, PromptCaller caller) {
        Prompt source = getVisiblePrompt(id, caller);
        String title = "Cópia de " + source.getTitle();
        if (title.length() > 255) title = title.substring(0, 255);

        Prompt copy = Prompt.builder()
                .title(title)
                .description(source.getDescription())
                .content(source.getContent())
                .type(source.getType())
                .visibility(PRIVATE)
                .status(source.getStatus())
                .impact(source.getImpact())
                .businessGoal(source.getBusinessGoal())
                .targetAudience(source.getTargetAudience())
                .gemLink(source.getGemLink())
                .architectureLink(source.getArchitectureLink())
                .authorId(caller.id())
                .authorName(snapshot != null ? snapshot.getAuthorName() : null)
                .authorRole(snapshot != null ? snapshot.getAuthorRole() : null)
                .authorSquad(snapshot != null ? snapshot.getAuthorSquad() : null)
                .authorAvatar(snapshot != null ? snapshot.getAuthorAvatar() : null)
                .tags(cleanTags(new LinkedHashSet<>(source.getTags())))
                .useCount(0)
                .forkCount(0)
                .build();
        Prompt saved = promptRepository.save(copy);
        promptRepository.incrementForkCount(id);
        return saved;
    }

    // ───────────── Comentários ─────────────

    @Transactional
    public PromptComment addComment(UUID promptId, PromptComment comment, PromptCaller caller) {
        Prompt prompt = getVisiblePrompt(promptId, caller);
        comment.setId(null);
        comment.setCreatedAt(null);
        comment.setAuthorId(caller.id());
        comment.setContent(comment.getContent().trim());
        comment.setPrompt(prompt);
        return commentRepository.save(comment);
    }

    @Transactional(readOnly = true)
    public List<PromptComment> getComments(UUID promptId, PromptCaller caller) {
        getVisiblePrompt(promptId, caller);
        return commentRepository.findByPromptIdOrderByCreatedAtAsc(promptId);
    }

    /** Autor do comentário, dono do item ou ADMIN. */
    @Transactional
    public void deleteComment(UUID promptId, UUID commentId, PromptCaller caller) {
        Prompt prompt = getVisiblePrompt(promptId, caller);
        PromptComment comment = commentRepository.findById(commentId)
                .filter(c -> c.getPrompt() != null && promptId.equals(c.getPrompt().getId()))
                .orElseThrow(() -> new IllegalArgumentException("Comment not found with id: " + commentId));
        if (!canManage(comment.getAuthorId(), caller) && !canManage(prompt.getAuthorId(), caller)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Você não pode remover este comentário");
        }
        commentRepository.delete(comment);
    }

    // ───────────── Coleções ─────────────

    /**
     * Cópia solta (não gerenciada) da coleção só com os itens que o chamador enxerga.
     * Nunca altere a lista da entidade gerenciada: o Hibernate gravaria a remoção na junção.
     */
    private PromptCollection viewOf(PromptCollection c, PromptCaller caller) {
        List<Prompt> visible = new ArrayList<>();
        for (Prompt p : c.getItems()) {
            if (canView(p, caller)) visible.add(p);
        }
        return PromptCollection.builder()
                .id(c.getId())
                .name(c.getName())
                .description(c.getDescription())
                .visibility(c.getVisibility())
                .ownerId(c.getOwnerId())
                .ownerName(c.getOwnerName())
                .items(visible)
                .createdAt(c.getCreatedAt())
                .build();
    }

    private boolean canViewCollection(PromptCollection c, PromptCaller caller) {
        return PUBLIC.equals(c.getVisibility()) || canManage(c.getOwnerId(), caller);
    }

    /** Listagem pública (API key / MCP): só coleções públicas, sem identidade. */
    @Transactional(readOnly = true)
    public Page<PromptCollection> listCollections(String visibility, String ownerId, Pageable pageable) {
        return listCollections(visibility, ownerId, null, pageable);
    }

    @Transactional(readOnly = true)
    public Page<PromptCollection> listCollections(String visibility, String ownerId, PromptCaller caller, Pageable pageable) {
        boolean hasOwnerId = ownerId != null && !ownerId.trim().isEmpty();
        boolean hasVisibility = visibility != null && !visibility.trim().isEmpty();
        Pageable p = pageable == null || pageable.isUnpaged() ? pageable
                : PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE),
                        pageable.getSort().isSorted() ? pageable.getSort() : Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<PromptCollection> page;
        if (hasOwnerId) {
            if (canManage(ownerId, caller)) {
                page = hasVisibility
                        ? promptCollectionRepository.findByOwnerIdAndVisibility(ownerId, visibility, p)
                        : promptCollectionRepository.findByOwnerId(ownerId, p);
            } else {
                page = promptCollectionRepository.findByOwnerIdAndVisibility(ownerId, PUBLIC, p);
            }
        } else if (hasVisibility && !PUBLIC.equals(visibility)) {
            // "private" nunca devolve coleções de terceiros: só as do próprio chamador.
            page = caller == null
                    ? Page.empty(p == null ? Pageable.unpaged() : p)
                    : promptCollectionRepository.findByOwnerIdAndVisibility(caller.id(), visibility, p);
        } else {
            page = promptCollectionRepository.findByVisibility(PUBLIC, p);
        }
        return page.map(c -> viewOf(c, caller));
    }

    @Transactional(readOnly = true)
    public PromptCollection getCollectionById(UUID id) {
        return promptCollectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Collection not found with id: " + id));
    }

    @Transactional(readOnly = true)
    public PromptCollection getCollectionFor(UUID id, PromptCaller caller) {
        PromptCollection c = getCollectionById(id);
        if (!canViewCollection(c, caller)) {
            throw new IllegalArgumentException("Collection not found with id: " + id);
        }
        return viewOf(c, caller);
    }

    /** Resolve os ids recebidos em itens reais, na ordem enviada, sem repetidos e só os que o chamador enxerga. */
    private List<Prompt> resolveItems(List<Prompt> requested, PromptCaller caller) {
        Set<UUID> seen = new LinkedHashSet<>();
        List<Prompt> resolved = new ArrayList<>();
        for (Prompt item : requested) {
            if (item == null || item.getId() == null || !seen.add(item.getId())) continue;
            resolved.add(getVisiblePrompt(item.getId(), caller));
        }
        return resolved;
    }

    @Transactional
    public PromptCollection createCollection(PromptCollection collection, PromptCaller caller) {
        collection.setId(null);
        collection.setCreatedAt(null);
        collection.setOwnerId(caller.id());
        collection.setName(collection.getName().trim());
        collection.setVisibility(normalizeVisibility(collection.getVisibility()));
        collection.setItems(collection.getItems() == null ? new ArrayList<>() : resolveItems(collection.getItems(), caller));
        return viewOf(promptCollectionRepository.save(collection), caller);
    }

    @Transactional
    public PromptCollection updateCollection(UUID id, PromptCollection updatedCollection, PromptCaller caller) {
        PromptCollection existing = getCollectionById(id);
        if (!canViewCollection(existing, caller)) {
            throw new IllegalArgumentException("Collection not found with id: " + id);
        }
        requireManage(existing.getOwnerId(), caller);
        existing.setName(updatedCollection.getName().trim());
        existing.setDescription(updatedCollection.getDescription());
        existing.setVisibility(normalizeVisibility(updatedCollection.getVisibility()));
        if (updatedCollection.getItems() != null) {
            existing.setItems(resolveItems(updatedCollection.getItems(), caller));
        }
        return viewOf(promptCollectionRepository.save(existing), caller);
    }

    @Transactional
    public void deleteCollection(UUID id, PromptCaller caller) {
        PromptCollection existing = getCollectionById(id);
        if (!canViewCollection(existing, caller)) {
            throw new IllegalArgumentException("Collection not found with id: " + id);
        }
        requireManage(existing.getOwnerId(), caller);
        promptCollectionRepository.delete(existing);
    }

    @Transactional
    public PromptCollection addItemToCollection(UUID collectionId, UUID promptId, PromptCaller caller) {
        PromptCollection collection = getCollectionById(collectionId);
        if (!canViewCollection(collection, caller)) {
            throw new IllegalArgumentException("Collection not found with id: " + collectionId);
        }
        requireManage(collection.getOwnerId(), caller);
        Prompt prompt = getVisiblePrompt(promptId, caller);
        boolean alreadyPresent = collection.getItems().stream()
                .anyMatch(p -> p.getId().equals(promptId));
        if (!alreadyPresent) {
            collection.getItems().add(prompt);
        }
        return viewOf(promptCollectionRepository.save(collection), caller);
    }

    @Transactional
    public PromptCollection removeItemFromCollection(UUID collectionId, UUID promptId, PromptCaller caller) {
        PromptCollection collection = getCollectionById(collectionId);
        if (!canViewCollection(collection, caller)) {
            throw new IllegalArgumentException("Collection not found with id: " + collectionId);
        }
        requireManage(collection.getOwnerId(), caller);
        collection.getItems().removeIf(p -> p.getId().equals(promptId));
        return viewOf(promptCollectionRepository.save(collection), caller);
    }
}
