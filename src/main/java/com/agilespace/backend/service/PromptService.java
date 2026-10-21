package com.agilespace.backend.service;

import com.agilespace.backend.domain.Prompt;
import com.agilespace.backend.domain.PromptComment;
import com.agilespace.backend.domain.PromptCollection;
import com.agilespace.backend.domain.PromptFavorite;
import com.agilespace.backend.domain.PromptUsageEvent;
import com.agilespace.backend.domain.PromptVersion;
import com.agilespace.backend.dto.PromptDto;
import com.agilespace.backend.dto.PromptFacetsDto;
import com.agilespace.backend.dto.PromptPageDto;
import com.agilespace.backend.dto.PromptUsageWeekDto;
import com.agilespace.backend.dto.PromptVersionDto;
import com.agilespace.backend.repository.PromptFavoriteRepository;
import com.agilespace.backend.repository.PromptFilter;
import com.agilespace.backend.repository.PromptQueryRepository;
import com.agilespace.backend.repository.PromptSpecs;
import com.agilespace.backend.repository.PromptUsageEventRepository;
import com.agilespace.backend.repository.PromptVersionRepository;
import com.agilespace.backend.repository.PromptCommentRepository;
import com.agilespace.backend.repository.PromptCollectionRepository;
import com.agilespace.backend.repository.PromptRepository;
import com.agilespace.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
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
    private final UserRepository userRepository;
    private final PromptFavoriteRepository favoriteRepository;
    private final PromptVersionRepository versionRepository;
    private final PromptUsageEventRepository usageRepository;
    private final PromptQueryRepository queryRepository;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_SEARCH_PAGE_SIZE = 100;
    private static final int MAX_USAGE_WEEKS = 52;

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

    /** Só "public" (qualquer caixa) vira público; qualquer outro valor cai no padrão seguro, privado. */
    private static String normalizeVisibility(String visibility) {
        return visibility != null && PUBLIC.equals(visibility.trim().toLowerCase(java.util.Locale.ROOT)) ? PUBLIC : PRIVATE;
    }

    /** Nome do autor vem do cadastro do usuário autenticado, nunca do corpo (evita assinar como outra pessoa). */
    private String authorNameOf(PromptCaller caller) {
        if (caller == null || caller.id() == null || userRepository == null) return null;
        return userRepository.findById(caller.id()).map(u -> u.getName()).orElse(null);
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
        prompt.setAuthorName(authorNameOf(caller));
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
        Prompt saved = promptRepository.save(prompt);
        recordVersion(saved, saved == null ? null : saved.getAuthorId(), saved == null ? null : saved.getAuthorName());
        return saved;
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
            return mutateAndVersion(existingOpt.get(), existing -> {
                existing.setContent(skillPrompt.getContent());
                if (skillPrompt.getDescription() != null && !skillPrompt.getDescription().isBlank()) {
                    existing.setDescription(skillPrompt.getDescription());
                }
                if (skillPrompt.getTags() != null && !skillPrompt.getTags().isEmpty()) {
                    existing.setTags(skillPrompt.getTags());
                }
                if (skillPrompt.getVisibility() != null) {
                    existing.setVisibility(normalizeVisibility(skillPrompt.getVisibility()));
                }
                if (skillPrompt.getStatus() != null) {
                    existing.setStatus(skillPrompt.getStatus());
                }
            }, skillPrompt.getAuthorId(), skillPrompt.getAuthorName(), false);
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
        return mutateAndVersion(existing, p -> {
            p.setTitle(updatedPrompt.getTitle() == null ? null : updatedPrompt.getTitle().trim());
            p.setDescription(updatedPrompt.getDescription());
            p.setContent(updatedPrompt.getContent());
            p.setType(updatedPrompt.getType());
            p.setVisibility(normalizeVisibility(updatedPrompt.getVisibility()));
            p.setStatus(updatedPrompt.getStatus());
            p.setImpact(updatedPrompt.getImpact());
            p.setBusinessGoal(updatedPrompt.getBusinessGoal());
            p.setTargetAudience(updatedPrompt.getTargetAudience());
            p.setGemLink(updatedPrompt.getGemLink());
            p.setArchitectureLink(updatedPrompt.getArchitectureLink());
            p.setTags(cleanTags(updatedPrompt.getTags()));
        }, caller.id(), authorNameOf(caller), false);
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
        recordUsage(id, caller, PromptUsageEvent.USE);
        return getPromptById(id);
    }

    @Transactional
    public Prompt incrementForkCount(UUID id, PromptCaller caller) {
        getVisiblePrompt(id, caller);
        promptRepository.incrementForkCount(id);
        recordUsage(id, caller, PromptUsageEvent.CLONE);
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
                .authorName(authorNameOf(caller))
                .authorRole(snapshot != null ? snapshot.getAuthorRole() : null)
                .authorSquad(snapshot != null ? snapshot.getAuthorSquad() : null)
                .authorAvatar(snapshot != null ? snapshot.getAuthorAvatar() : null)
                .tags(cleanTags(new LinkedHashSet<>(source.getTags())))
                .useCount(0)
                .forkCount(0)
                .build();
        Prompt saved = promptRepository.save(copy);
        recordVersion(saved, caller.id(), authorNameOf(caller));
        promptRepository.incrementForkCount(id);
        recordUsage(id, caller, PromptUsageEvent.CLONE);
        return saved;
    }

    // ───────────── Busca, filtros e facets (servidor) ─────────────

    private static Sort sortOf(String sort) {
        String s = sort == null ? "recent" : sort.trim().toLowerCase(java.util.Locale.ROOT);
        Sort tie = Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.asc("id"));
        return switch (s) {
            case "used" -> Sort.by(Sort.Order.desc("useCount").nullsLast()).and(tie);
            case "cloned" -> Sort.by(Sort.Order.desc("forkCount").nullsLast()).and(tie);
            case "az" -> Sort.by(Sort.Order.asc("title").ignoreCase()).and(tie);
            default -> tie;
        };
    }

    /** Busca paginada com filtros e ordenação no banco. caller == null é visitante (só públicos). */
    @Transactional(readOnly = true)
    public Page<Prompt> searchPrompts(PromptFilter filter, String sort, PromptCaller caller, int page, int size) {
        Pageable p = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SEARCH_PAGE_SIZE), sortOf(sort));
        return promptRepository.findAll(PromptSpecs.filtered(filter, caller), p);
    }

    @Transactional(readOnly = true)
    public PromptPageDto<PromptDto> searchPromptDtos(PromptFilter filter, String sort, PromptCaller caller, int page, int size) {
        Page<Prompt> result = searchPrompts(filter, sort, caller, page, size);
        Set<UUID> favorites = favoriteIds(caller, result.getContent());
        List<PromptDto> content = result.getContent().stream()
                .map(p -> dtoOf(p, caller, favorites.contains(p.getId())))
                .toList();
        return new PromptPageDto<>(content, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public PromptDto getVisiblePromptDto(UUID id, PromptCaller caller) {
        Prompt p = getVisiblePrompt(id, caller);
        return dtoOf(p, caller, isFavorite(caller, id));
    }

    @Transactional(readOnly = true)
    public PromptFacetsDto facets(PromptFilter filter, PromptCaller caller) {
        long total = promptRepository.count(PromptSpecs.filtered(filter, caller));
        // Cada faceta ignora o próprio filtro, para a pílula mostrar a distribuição e não só o item marcado.
        Map<String, Long> typeCounts = queryRepository.countByType(PromptSpecs.filtered(filter.withoutType(), caller));
        List<PromptFacetsDto.TagCount> topTags = queryRepository.topTags(PromptSpecs.filtered(filter.withoutTag(), caller), 10);
        PromptFilter base = new PromptFilter(null, null, null, null, null, null, false, null);
        long items = promptRepository.count(PromptSpecs.filtered(base, caller));
        long copies = queryRepository.sumUseCount(PromptSpecs.filtered(base, caller));
        long collections = caller == null || caller.id() == null ? promptCollectionRepository.countPublic()
                : caller.isAdmin() ? promptCollectionRepository.count()
                : promptCollectionRepository.countVisibleTo(caller.id());
        return new PromptFacetsDto(total, typeCounts, topTags, new PromptFacetsDto.Totals(items, copies, collections));
    }

    PromptDto dtoOf(Prompt p, PromptCaller caller, boolean favorite) {
        return PromptDto.of(p, favorite, canManage(p.getAuthorId(), caller), caller != null && caller.isAdmin());
    }

    private Set<UUID> favoriteIds(PromptCaller caller, List<Prompt> prompts) {
        if (caller == null || caller.id() == null || prompts.isEmpty()) return Set.of();
        List<UUID> ids = prompts.stream().map(Prompt::getId).toList();
        Set<UUID> out = new HashSet<>();
        for (PromptFavorite f : favoriteRepository.findByUserIdAndPromptIdIn(caller.id(), ids)) out.add(f.getPromptId());
        return out;
    }

    private boolean isFavorite(PromptCaller caller, UUID promptId) {
        return caller != null && caller.id() != null
                && favoriteRepository.existsById(new PromptFavorite.Key(caller.id(), promptId));
    }

    // ───────────── Favoritos ─────────────

    /** Idempotente: favoritar duas vezes ou desfavoritar quem não está favoritado não é erro. */
    @Transactional
    public boolean setFavorite(UUID promptId, PromptCaller caller, boolean favorite) {
        getVisiblePrompt(promptId, caller);
        PromptFavorite.Key key = new PromptFavorite.Key(caller.id(), promptId);
        boolean exists = favoriteRepository.existsById(key);
        if (favorite && !exists) {
            favoriteRepository.save(PromptFavorite.builder()
                    .userId(caller.id()).promptId(promptId).createdAt(Instant.now()).build());
        } else if (!favorite && exists) {
            favoriteRepository.deleteById(key);
        }
        return favorite;
    }

    // ───────────── Versões ─────────────

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String tagsJson(Set<String> tags) {
        List<String> sorted = tags == null ? List.of() : tags.stream().filter(Objects::nonNull).sorted().toList();
        try {
            return JSON.writeValueAsString(sorted);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static List<String> parseTags(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Campos que, ao mudar, geram uma versão nova (o resto, como link e contadores, não). */
    private static String signature(Prompt p) {
        return String.join("\u0000", nz(p.getTitle()), nz(p.getDescription()), nz(p.getContent()), nz(p.getType()),
                normalizeVisibility(p.getVisibility()), nz(p.getStatus()), tagsJson(p.getTags()));
    }

    private static Instant toInstant(java.time.LocalDateTime t) {
        return t == null ? Instant.now() : t.atZone(java.time.ZoneId.systemDefault()).toInstant();
    }

    private PromptVersion snapshot(Prompt p, int number, String by, String byName, Instant at) {
        return PromptVersion.builder()
                .promptId(p.getId()).versionNumber(number)
                .title(nz(p.getTitle())).description(p.getDescription()).content(p.getContent())
                .type(p.getType()).visibility(normalizeVisibility(p.getVisibility())).status(p.getStatus())
                .tags(tagsJson(p.getTags()))
                .changedBy(by).changedByName(byName).changedAt(at)
                .build();
    }

    private void recordVersion(Prompt p, String by, String byName) {
        if (p == null || p.getId() == null || versionRepository == null) return;
        int next = versionRepository.maxVersionNumber(p.getId()) + 1;
        versionRepository.save(snapshot(p, next, by, byName, Instant.now()));
    }

    /**
     * Aplica a edição e grava uma versão se algo versionado mudou (ou sempre, com force). Item antigo
     * sem histórico ganha antes a versão 1 com o estado anterior à edição.
     */
    private Prompt mutateAndVersion(Prompt existing, Consumer<Prompt> mutate, String by, String byName, boolean force) {
        String before = signature(existing);
        PromptVersion baseline = versionRepository.maxVersionNumber(existing.getId()) == 0
                ? snapshot(existing, 1, existing.getAuthorId(), existing.getAuthorName(), toInstant(existing.getCreatedAt()))
                : null;
        mutate.accept(existing);
        Prompt saved = promptRepository.save(existing);
        if (force || !before.equals(signature(saved))) {
            if (baseline != null) versionRepository.save(baseline);
            recordVersion(saved, by, byName);
        }
        return saved;
    }

    /** Item que nunca foi editado ainda não tem linhas: mostra o estado atual como versão 1. */
    private PromptVersion virtualVersion(Prompt p) {
        return snapshot(p, 1, p.getAuthorId(), p.getAuthorName(), toInstant(p.getCreatedAt()));
    }

    private static PromptVersionDto versionDto(PromptVersion v, boolean current, boolean full) {
        return new PromptVersionDto(v.getVersionNumber(), v.getTitle(), v.getChangedBy(), v.getChangedByName(),
                v.getChangedAt(), current,
                full ? v.getDescription() : null, full ? v.getContent() : null, full ? v.getType() : null,
                full ? v.getVisibility() : null, full ? v.getStatus() : null, full ? parseTags(v.getTags()) : null);
    }

    @Transactional(readOnly = true)
    public List<PromptVersionDto> listVersions(UUID promptId, PromptCaller caller) {
        Prompt p = getVisiblePrompt(promptId, caller);
        List<PromptVersion> versions = versionRepository.findByPromptIdOrderByVersionNumberDesc(promptId);
        if (versions.isEmpty()) return List.of(versionDto(virtualVersion(p), true, false));
        int latest = versions.get(0).getVersionNumber();
        return versions.stream().map(v -> versionDto(v, v.getVersionNumber() == latest, false)).toList();
    }

    @Transactional(readOnly = true)
    public PromptVersionDto getVersion(UUID promptId, int number, PromptCaller caller) {
        Prompt p = getVisiblePrompt(promptId, caller);
        List<PromptVersion> versions = versionRepository.findByPromptIdOrderByVersionNumberDesc(promptId);
        if (versions.isEmpty()) {
            if (number != 1) throw new IllegalArgumentException("Version not found: " + number);
            return versionDto(virtualVersion(p), true, true);
        }
        int latest = versions.get(0).getVersionNumber();
        return versions.stream().filter(v -> v.getVersionNumber() == number).findFirst()
                .map(v -> versionDto(v, v.getVersionNumber() == latest, true))
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + number));
    }

    /** Devolve o item ao conteúdo da versão e grava isso como uma NOVA versão (o histórico nunca é reescrito). */
    @Transactional
    public Prompt restoreVersion(UUID promptId, int number, PromptCaller caller) {
        Prompt existing = getVisiblePrompt(promptId, caller);
        requireManage(existing.getAuthorId(), caller);
        if (versionRepository.maxVersionNumber(promptId) == 0 && number == 1) {
            return existing; // versão 1 virtual = estado atual: nada a restaurar
        }
        PromptVersion v = versionRepository.findByPromptIdAndVersionNumber(promptId, number)
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + number));
        return mutateAndVersion(existing, p -> {
            p.setTitle(v.getTitle());
            p.setDescription(v.getDescription());
            p.setContent(v.getContent());
            p.setType(v.getType());
            p.setVisibility(normalizeVisibility(v.getVisibility()));
            p.setStatus(v.getStatus());
            p.setTags(cleanTags(new LinkedHashSet<>(parseTags(v.getTags()))));
        }, caller.id(), authorNameOf(caller), true);
    }

    // ───────────── Uso por semana ─────────────

    private void recordUsage(UUID promptId, PromptCaller caller, String kind) {
        if (usageRepository == null) return;
        usageRepository.save(PromptUsageEvent.builder()
                .promptId(promptId).userId(caller == null ? null : caller.id())
                .kind(kind).occurredAt(Instant.now()).build());
    }

    /** Todas as semanas do intervalo (segunda UTC), com zeros, da mais antiga para a atual. */
    @Transactional(readOnly = true)
    public List<PromptUsageWeekDto> usageByWeek(UUID promptId, int weeks, PromptCaller caller) {
        getVisiblePrompt(promptId, caller);
        int n = Math.min(Math.max(weeks, 1), MAX_USAGE_WEEKS);
        LocalDate thisMonday = LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate first = thisMonday.minusWeeks(n - 1L);
        Map<LocalDate, long[]> buckets = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) buckets.put(first.plusWeeks(i), new long[2]);
        Instant since = first.atStartOfDay(ZoneOffset.UTC).toInstant();
        for (PromptUsageEvent e : usageRepository.findByPromptIdAndOccurredAtGreaterThanEqual(promptId, since)) {
            LocalDate monday = e.getOccurredAt().atZone(ZoneOffset.UTC).toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            long[] b = buckets.get(monday);
            if (b == null) continue;
            if (PromptUsageEvent.CLONE.equals(e.getKind())) b[1]++; else b[0]++;
        }
        List<PromptUsageWeekDto> out = new ArrayList<>();
        buckets.forEach((d, b) -> out.add(new PromptUsageWeekDto(d.toString(), b[0], b[1])));
        return out;
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
                .itemCount(c.getItems().size())
                .hiddenCount(c.getItems().size() - visible.size())
                .canEdit(canManage(c.getOwnerId(), caller))
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

    /** Detalhe anônimo: só coleção pública; privada e inexistente respondem igual (IllegalArgumentException). */
    @Transactional(readOnly = true, noRollbackFor = IllegalArgumentException.class)
    public PromptCollection getPublicCollection(UUID id) {
        PromptCollection c = getCollectionById(id);
        if (!PUBLIC.equals(c.getVisibility())) {
            throw new IllegalArgumentException("Collection not found with id: " + id);
        }
        return viewOf(c, null);
    }

    @Transactional(readOnly = true, noRollbackFor = IllegalArgumentException.class)
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
