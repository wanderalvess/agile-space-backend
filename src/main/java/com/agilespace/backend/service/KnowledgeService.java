package com.agilespace.backend.service;

import com.agilespace.backend.domain.KnowledgeConversation;
import com.agilespace.backend.domain.KnowledgeDocument;
import com.agilespace.backend.domain.KnowledgeDocumentVersion;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.KnowledgeVersionDto;
import com.agilespace.backend.repository.KnowledgeDocumentVersionRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.domain.KnowledgeTokenUsage;
import com.agilespace.backend.domain.KnowledgeUserAiSettings;
import com.agilespace.backend.repository.KnowledgeConversationRepository;
import com.agilespace.backend.repository.KnowledgeRepository;
import com.agilespace.backend.repository.KnowledgeTokenUsageRepository;
import com.agilespace.backend.repository.KnowledgeUserAiSettingsRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeConversationRepository knowledgeConversationRepository;
    private final KnowledgeUserAiSettingsRepository knowledgeUserAiSettingsRepository;
    private final KnowledgeTokenUsageRepository knowledgeTokenUsageRepository;
    private final ObjectMapper objectMapper;
    private final KnowledgeDocumentVersionRepository versionRepository;
    private final SquadAccessService squadAccessService;
    private final UserRepository userRepository;

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "o", "as", "os", "um", "uma", "uns", "umas",
            "de", "do", "da", "dos", "das", "em", "no", "na", "nos", "nas",
            "por", "pelo", "pela", "pelos", "pelas", "para", "pra", "com", "sem",
            "qual", "quais", "quem", "como", "onde", "quando", "porque", "que",
            "este", "esta", "esse", "essa", "aquele", "aquela", "tem", "temos", "e", "ou"
    );

    // Embeddings já saem normalizados do pipeline (transformers.js, normalize: true),
    // então dot product == cosine similarity — evita recalcular norma a cada comparação.
    // Valor calibrado observando scores reais (MiniLM multilingue comprime a faixa de cosine
    // similarity — sinônimo genuíno ficou ~0.23, não os 0.7+ que se poderia esperar
    // ingenuamente). Abaixo disso o resultado é ruído, não relevância real. Recalibrar
    // se a base de conhecimento real mostrar muito falso positivo/negativo em produção.
    private static final float MIN_SIMILARITY = 0.2f;

    static final int MAX_VERSIONS = 50;
    static final String NO_PERMISSION = "Só o autor ou a liderança editam este documento.";
    static final int MAX_TITLE = 255;
    static final int MAX_META = 255;
    static final int MAX_CONTENT = 2_000_000;
    static final int MAX_TAGS = 50;
    static final int MAX_TAG_LENGTH = 100;
    static final int MAX_MESSAGE_CHARS = 100_000;
    static final int MAX_CONVERSATION_CHARS = 2_000_000;
    public static final int MAX_EMBEDDING_DIMS = 2048;
    static final long MAX_TOKENS_PER_CALL = 200_000L;
    static final Set<String> DOC_STATUS = Set.of("indexed", "published", "deleted");

    // Overload preservado para compatibilidade de origem com chamadas existentes sem filtro de status.
    @Transactional(readOnly = true)
    public Page<KnowledgeDocument> listDocuments(String query, Set<String> tags, Pageable pageable) {
        return listDocuments(query, tags, null, pageable);
    }

    @Transactional(readOnly = true)
    public Page<KnowledgeDocument> listDocuments(String query, Set<String> tags, String status, Pageable pageable) {
        // Sem ordenação pedida, a página vinha em ordem arbitrária do banco: mais recentes primeiro.
        if (pageable.isPaged() && pageable.getSort().isUnsorted()) {
            pageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "updatedAt"));
        }
        Page<KnowledgeDocument> docsPage;
        if (query != null && !query.trim().isEmpty()) {
            String trimmedQuery = query.trim();
            // Tenta busca exata inicial
            docsPage = knowledgeRepository.searchActive(trimmedQuery, "deleted", pageable);

            // Se devolver poucos/nenhum resultado e a busca tiver múltiplas palavras, extrai palavras-chave
            if (docsPage.getContent().size() < 3 && trimmedQuery.contains(" ")) {
                List<String> keywords = java.util.Arrays.stream(trimmedQuery.toLowerCase().split("\\s+"))
                        .map(w -> w.replaceAll("[^a-zA-Z0-9\\-_]", ""))
                        .filter(w -> w.length() > 1 && !STOP_WORDS.contains(w))
                        .collect(Collectors.toList());

                if (!keywords.isEmpty()) {
                    List<KnowledgeDocument> allDocs = knowledgeRepository.findByStatusNot("deleted", Pageable.unpaged()).getContent();
                    List<KnowledgeDocument> matched = allDocs.stream()
                            .map(doc -> {
                                String text = (doc.getTitle() + " " + doc.getContent() + " " + doc.getCategory() + " " + doc.getFullPath()).toLowerCase();
                                long matches = keywords.stream().filter(text::contains).count();
                                return new java.util.AbstractMap.SimpleEntry<>(doc, matches);
                            })
                            .filter(entry -> entry.getValue() > 0)
                            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                            .map(java.util.AbstractMap.SimpleEntry::getKey)
                            .collect(Collectors.toList());

                    if (!matched.isEmpty()) {
                        int start = (int) pageable.getOffset();
                        int end = Math.min(start + pageable.getPageSize(), matched.size());
                        List<KnowledgeDocument> pageContent = start < matched.size() ? matched.subList(start, end) : List.of();
                        docsPage = new PageImpl<>(pageContent, pageable, matched.size());
                    }
                }
            }
        } else if (status != null && !status.trim().isEmpty()) {
            docsPage = knowledgeRepository.findByStatus(status, pageable);
        } else {
            docsPage = knowledgeRepository.findByStatusNot("deleted", pageable);
        }

        // Se houver filtro de tags, filtramos na memória
        if (tags != null && !tags.isEmpty()) {
            List<KnowledgeDocument> filteredList = docsPage.getContent().stream()
                    .filter(doc -> doc.getTags().containsAll(tags))
                    .collect(Collectors.toList());
            return new PageImpl<>(filteredList, pageable, docsPage.getTotalElements());
        }

        return docsPage;
    }

    @Transactional(readOnly = true)
    public Page<KnowledgeDocument> semanticSearch(float[] queryEmbedding, Pageable pageable) {
        List<KnowledgeDocument> allDocs = knowledgeRepository.findByStatusNot("deleted", Pageable.unpaged()).getContent();

        List<java.util.AbstractMap.SimpleEntry<KnowledgeDocument, Float>> scored = allDocs.stream()
                .filter(doc -> doc.getEmbedding() != null && doc.getEmbedding().length == queryEmbedding.length)
                .map(doc -> new java.util.AbstractMap.SimpleEntry<>(doc, dotProduct(doc.getEmbedding(), queryEmbedding)))
                .filter(entry -> entry.getValue() >= MIN_SIMILARITY)
                .sorted((a, b) -> Float.compare(b.getValue(), a.getValue()))
                .collect(Collectors.toList());

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), scored.size());
        List<KnowledgeDocument> pageContent = start < scored.size()
                ? scored.subList(start, end).stream().map(java.util.AbstractMap.SimpleEntry::getKey).collect(Collectors.toList())
                : List.of();

        return new PageImpl<>(pageContent, pageable, scored.size());
    }

    private static float dotProduct(float[] a, float[] b) {
        float sum = 0f;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    @Transactional(readOnly = true)
    public KnowledgeDocument getDocumentById(UUID id) {
        return knowledgeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Document not found with id: " + id));
    }

    @Transactional
    public KnowledgeDocument saveOrUpdateDocument(KnowledgeDocument doc) {
        return saveOrUpdateDocument(doc, false);
    }

    @Transactional
    public KnowledgeDocument saveOrUpdateDocument(KnowledgeDocument doc, boolean isAdmin) {
        validateDocument(doc);
        // Documento novo nunca herda id/contadores/lixeira/squad vindos do corpo: um POST com o id de um
        // documento existente virava "merge" e sobrescrevia (inclusive a autoria) o de outra pessoa.
        doc.setId(null);
        doc.setViews(0);
        doc.setDeletedAt(null);
        doc.setDeletedBy(null);
        doc.setSquadId(null);
        doc.setCanEdit(null);
        if ("deleted".equals(doc.getStatus())) {
            throw badRequest("Para apagar um documento use a exclusão (lixeira).");
        }
        // Se for uma importação do TDN, verifica se o documento com esse tdnId já existe
        if (doc.getTdnId() != null && !doc.getTdnId().trim().isEmpty()) {
            Optional<KnowledgeDocument> existingOpt = knowledgeRepository.findByTdnId(doc.getTdnId());
            if (existingOpt.isPresent()) {
                KnowledgeDocument existing = existingOpt.get();
                // Reimportar sobrescreve o documento: vale a mesma regra de edição.
                requireCanEdit(existing, doc.getAuthorId(), isAdmin);
                boolean changed = contentChanged(existing, doc);
                if (changed) {
                    ensureBaseline(existing);
                }
                existing.setTitle(doc.getTitle());
                existing.setContent(doc.getContent());
                existing.setCategory(doc.getCategory());
                existing.setFullPath(doc.getFullPath());
                existing.setModuleId(doc.getModuleId());
                existing.setModuleName(doc.getModuleName());
                existing.setFolderId(doc.getFolderId());
                existing.setFolderName(doc.getFolderName());
                existing.setStatus(doc.getStatus() != null ? doc.getStatus() : "published");
                existing.setTags(doc.getTags());
                existing.setByteSize(doc.getByteSize());
                existing.setUpdatedBy(doc.getAuthorId());
                if (doc.getEmbedding() != null) {
                    existing.setEmbedding(doc.getEmbedding());
                } else if (changed) {
                    existing.setEmbedding(null);
                }
                KnowledgeDocument saved = knowledgeRepository.save(existing);
                if (changed) {
                    recordVersion(saved, doc.getAuthorId(), LocalDateTime.now(ZoneOffset.UTC));
                }
                return saved;
            }
        }

        // Se for novo
        if (doc.getStatus() == null) {
            doc.setStatus("published");
        }
        doc.setSquadId(squadOfUser(doc.getAuthorId()));
        KnowledgeDocument saved = knowledgeRepository.save(doc);
        recordVersion(saved, saved.getAuthorId(), LocalDateTime.now(ZoneOffset.UTC));
        return saved;
    }

    @Transactional
    public KnowledgeDocument updateDocument(UUID id, KnowledgeDocument updatedDoc, String callerId, boolean isAdmin) {
        validateDocument(updatedDoc);
        KnowledgeDocument existing = getDocumentById(id);
        // Editar, mover para a lixeira e restaurar: só autor, liderança da squad do documento ou ADMIN.
        requireCanEdit(existing, callerId, isAdmin);
        String newStatus = updatedDoc.getStatus() != null ? updatedDoc.getStatus() : existing.getStatus();
        boolean deleting = "deleted".equals(newStatus) && !"deleted".equals(existing.getStatus());
        boolean changed = contentChanged(existing, updatedDoc);
        if (changed) {
            ensureBaseline(existing);
        }
        existing.setTitle(updatedDoc.getTitle());
        existing.setContent(updatedDoc.getContent());
        existing.setCategory(updatedDoc.getCategory());
        existing.setFullPath(updatedDoc.getFullPath());
        existing.setModuleId(updatedDoc.getModuleId());
        existing.setModuleName(updatedDoc.getModuleName());
        existing.setFolderId(updatedDoc.getFolderId());
        existing.setFolderName(updatedDoc.getFolderName());
        // Status ausente no corpo não pode virar null: documento com status nulo some de todas as listas.
        existing.setStatus(newStatus);
        if (deleting) {
            existing.setDeletedAt(LocalDateTime.now());
            existing.setDeletedBy(callerId);
        } else if (!"deleted".equals(newStatus)) {
            existing.setDeletedAt(null);
            existing.setDeletedBy(null);
        }
        existing.setTags(updatedDoc.getTags() != null ? updatedDoc.getTags() : existing.getTags());
        existing.setByteSize(updatedDoc.getByteSize());
        existing.setUpdatedBy(callerId != null ? callerId : updatedDoc.getUpdatedBy());
        if (updatedDoc.getEmbedding() != null) {
            existing.setEmbedding(updatedDoc.getEmbedding());
        } else if (changed) {
            // O vetor antigo descreve o texto antigo; sem um novo, o documento sai da busca semântica
            // até ser reindexado (melhor do que achar o documento pelo conteúdo que ele não tem mais).
            existing.setEmbedding(null);
        }
        KnowledgeDocument saved = knowledgeRepository.save(existing);
        if (changed) {
            recordVersion(saved, callerId, LocalDateTime.now(ZoneOffset.UTC));
        }
        return saved;
    }

    // ----- Permissão de edição

    /** Autor, ADMIN ou liderança da squad do documento. Documento sem autor registrado: só ADMIN. */
    public boolean canEdit(KnowledgeDocument doc, String callerId, boolean isAdmin) {
        return canEdit(doc, callerId, isAdmin, new HashMap<>());
    }

    private boolean canEdit(KnowledgeDocument doc, String callerId, boolean isAdmin, Map<String, Boolean> leadershipBySquad) {
        if (isAdmin) {
            return true;
        }
        if (callerId == null || callerId.isBlank() || doc.getAuthorId() == null || doc.getAuthorId().isBlank()) {
            return false;
        }
        if (callerId.equals(doc.getAuthorId())) {
            return true;
        }
        String squad = doc.getSquadId();
        if (squad == null || squad.isBlank() || squadAccessService == null) {
            return false;
        }
        return leadershipBySquad.computeIfAbsent(squad, s -> squadAccessService.isSquadLeadership(s, callerId, null));
    }

    private void requireCanEdit(KnowledgeDocument doc, String callerId, boolean isAdmin) {
        if (!canEdit(doc, callerId, isAdmin)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, NO_PERMISSION);
        }
    }

    /** Preenche {@code canEdit} em cada documento (uma checagem de liderança por squad, não por documento). */
    public void markEditable(Iterable<KnowledgeDocument> docs, String callerId, boolean isAdmin) {
        Map<String, Boolean> cache = new HashMap<>();
        for (KnowledgeDocument d : docs) {
            d.setCanEdit(canEdit(d, callerId, isAdmin, cache));
        }
    }

    private String squadOfUser(String userId) {
        if (userId == null || userRepository == null) {
            return null;
        }
        // Só DE ONDE vem a squad muda com app.membership.authz (a base de conhecimento segue compartilhada: o escopo de leitura não muda).
        return userRepository.findById(userId)
                .map(u -> squadAccessService != null && squadAccessService.authzMode() != null && squadAccessService.authzMode() != AuthzMode.LEGACY
                        ? squadAccessService.activeSquadFor("knowledge", u) : u.getDefaultProjectId())
                .filter(sq -> !sq.isBlank()).orElse(null);
    }

    // ----- Histórico de versões

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

    private boolean contentChanged(KnowledgeDocument existing, KnowledgeDocument incoming) {
        if (!Objects.equals(existing.getTitle(), incoming.getTitle())) return true;
        if (!nullToEmpty(existing.getContent()).equals(nullToEmpty(incoming.getContent()))) return true;
        if (incoming.getTags() == null) return false;
        return !new TreeSet<>(existing.getTags() == null ? Set.<String>of() : existing.getTags())
                .equals(new TreeSet<>(incoming.getTags()));
    }

    private String tagsJson(Set<String> tags) {
        try {
            return objectMapper.writeValueAsString(new TreeSet<>(tags == null ? Set.<String>of() : tags));
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private List<String> parseTags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /** Guarda o estado atual como uma nova versão e descarta as mais antigas além do limite. */
    private void recordVersion(KnowledgeDocument doc, String changedBy, LocalDateTime at) {
        int next = versionRepository.findTopByDocumentIdOrderByVersionNumberDesc(doc.getId())
                .map(v -> v.getVersionNumber() + 1).orElse(1);
        versionRepository.save(KnowledgeDocumentVersion.builder()
                .documentId(doc.getId())
                .versionNumber(next)
                .title(doc.getTitle())
                .content(doc.getContent())
                .tags(tagsJson(doc.getTags()))
                .category(doc.getCategory())
                .fullPath(doc.getFullPath())
                .changedBy(changedBy)
                .changedAt(at)
                .build());
        List<KnowledgeDocumentVersion> all = versionRepository.findByDocumentIdOrderByVersionNumberDesc(doc.getId());
        if (all.size() > MAX_VERSIONS) {
            versionRepository.deleteAll(all.subList(MAX_VERSIONS, all.size()));
        }
    }

    /** Documento que ainda não tem histórico (criado antes desta função): registra o estado atual como versão inicial. */
    private void ensureBaseline(KnowledgeDocument doc) {
        if (versionRepository.countByDocumentId(doc.getId()) == 0) {
            LocalDateTime at = doc.getUpdatedAt() != null ? doc.getUpdatedAt()
                    : doc.getCreatedAt() != null ? doc.getCreatedAt() : LocalDateTime.now(ZoneOffset.UTC);
            recordVersion(doc, doc.getUpdatedBy() != null ? doc.getUpdatedBy() : doc.getAuthorId(), at);
        }
    }

    private KnowledgeVersionDto toDto(KnowledgeDocumentVersion v, int currentNumber, boolean full, Map<String, String> names) {
        String name = v.getChangedBy() == null ? null : names.computeIfAbsent(v.getChangedBy(),
                uid -> userRepository == null ? uid : userRepository.findById(uid).map(User::getName).orElse(uid));
        return new KnowledgeVersionDto(v.getVersionNumber(), v.getTitle(), v.getChangedBy(), name, v.getChangedAt(),
                v.getVersionNumber() == currentNumber,
                full ? v.getContent() : null, full ? parseTags(v.getTags()) : null,
                full ? v.getCategory() : null, full ? v.getFullPath() : null);
    }

    @Transactional
    public List<KnowledgeVersionDto> listVersions(UUID id) {
        KnowledgeDocument doc = getDocumentById(id);
        ensureBaseline(doc);
        List<KnowledgeDocumentVersion> versions = versionRepository.findByDocumentIdOrderByVersionNumberDesc(id);
        int current = versions.isEmpty() ? 0 : versions.get(0).getVersionNumber();
        Map<String, String> names = new HashMap<>();
        return versions.stream().map(v -> toDto(v, current, false, names)).collect(Collectors.toList());
    }

    @Transactional
    public KnowledgeVersionDto getVersion(UUID id, int number) {
        KnowledgeDocument doc = getDocumentById(id);
        ensureBaseline(doc);
        KnowledgeDocumentVersion v = versionRepository.findByDocumentIdAndVersionNumber(id, number)
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + number));
        int current = versionRepository.findTopByDocumentIdOrderByVersionNumberDesc(id).map(KnowledgeDocumentVersion::getVersionNumber).orElse(0);
        return toDto(v, current, true, new HashMap<>());
    }

    /**
     * Restaurar uma versão cria uma NOVA versão com aquele conteúdo (o histórico só cresce). O vetor de busca
     * é descartado: o chamador reindexa em seguida (a tela faz isso), então a busca semântica nunca usa texto antigo.
     */
    @Transactional
    public KnowledgeDocument restoreVersion(UUID id, int number, String callerId, boolean isAdmin) {
        KnowledgeDocument doc = getDocumentById(id);
        requireCanEdit(doc, callerId, isAdmin);
        ensureBaseline(doc);
        KnowledgeDocumentVersion v = versionRepository.findByDocumentIdAndVersionNumber(id, number)
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + number));
        doc.setTitle(v.getTitle());
        doc.setContent(v.getContent());
        doc.setTags(new java.util.HashSet<>(parseTags(v.getTags())));
        if (v.getCategory() != null) doc.setCategory(v.getCategory());
        if (v.getFullPath() != null) doc.setFullPath(v.getFullPath());
        doc.setByteSize(v.getContent() == null ? 0L : (long) v.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        doc.setUpdatedBy(callerId);
        doc.setEmbedding(null);
        KnowledgeDocument saved = knowledgeRepository.save(doc);
        recordVersion(saved, callerId, LocalDateTime.now(ZoneOffset.UTC));
        return saved;
    }

    static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    static void validateDocument(KnowledgeDocument doc) {
        if (doc.getTitle() == null || doc.getTitle().isBlank()) {
            throw badRequest("O título é obrigatório.");
        }
        doc.setTitle(doc.getTitle().trim());
        if (doc.getTitle().length() > MAX_TITLE) {
            throw badRequest("O título passa do limite de " + MAX_TITLE + " caracteres.");
        }
        if (doc.getContent() != null && doc.getContent().length() > MAX_CONTENT) {
            throw badRequest("O conteúdo passa do limite de " + MAX_CONTENT + " caracteres.");
        }
        for (String field : new String[]{doc.getCategory(), doc.getFullPath(), doc.getModuleId(), doc.getModuleName(),
                doc.getFolderId(), doc.getFolderName(), doc.getTdnId()}) {
            if (field != null && field.length() > MAX_META) {
                throw badRequest("Categoria, caminho e demais campos passam do limite de " + MAX_META + " caracteres.");
            }
        }
        if (doc.getStatus() != null && !DOC_STATUS.contains(doc.getStatus())) {
            throw badRequest("Status inválido: " + doc.getStatus());
        }
        if (doc.getTags() != null) {
            if (doc.getTags().size() > MAX_TAGS) {
                throw badRequest("No máximo " + MAX_TAGS + " tags por documento.");
            }
            for (String tag : doc.getTags()) {
                if (tag != null && tag.length() > MAX_TAG_LENGTH) {
                    throw badRequest("Cada tag pode ter até " + MAX_TAG_LENGTH + " caracteres.");
                }
            }
        }
        if (doc.getEmbedding() != null && doc.getEmbedding().length > MAX_EMBEDDING_DIMS) {
            throw badRequest("Embedding inválido.");
        }
    }

    @Transactional
    public KnowledgeDocument deleteDocument(UUID id, String callerId, boolean isAdmin) {
        KnowledgeDocument doc = getDocumentById(id);
        requireCanEdit(doc, callerId, isAdmin);
        doc.setStatus("deleted");
        doc.setDeletedAt(LocalDateTime.now());
        doc.setDeletedBy(callerId);
        return knowledgeRepository.save(doc);
    }

    @Transactional
    public KnowledgeDocument incrementViews(UUID id) {
        KnowledgeDocument doc = getDocumentById(id);
        doc.setViews(doc.getViews() + 1);
        return knowledgeRepository.save(doc);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeConversation> listConversations(String userId) {
        return knowledgeConversationRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public KnowledgeConversation getConversation(UUID id, String userId) {
        KnowledgeConversation existing = knowledgeConversationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found with id: " + id));
        // Conversas são privadas por usuário e nunca compartilhadas: valida a posse antes de retornar.
        if (!existing.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Conversation not found with id: " + id);
        }
        return existing;
    }

    @Transactional
    public KnowledgeConversation createConversation(String userId, String title) {
        KnowledgeConversation conversation = KnowledgeConversation.builder()
                .userId(userId)
                .title(cleanTitle(title))
                .messages("[]")
                .updatedAt(LocalDateTime.now())
                .build();
        return knowledgeConversationRepository.save(conversation);
    }

    @Transactional
    public KnowledgeConversation renameConversation(UUID id, String userId, String newTitle) {
        KnowledgeConversation existing = getConversation(id, userId);
        existing.setTitle(cleanTitle(newTitle));
        existing.setUpdatedAt(LocalDateTime.now());
        return knowledgeConversationRepository.save(existing);
    }

    @Transactional
    public KnowledgeConversation appendMessage(UUID id, String userId, Object message) {
        KnowledgeConversation existing = getConversation(id, userId);
        try {
            List<Object> messages = existing.getMessages() != null
                    ? objectMapper.readValue(existing.getMessages(), new TypeReference<List<Object>>() {})
                    : new ArrayList<>();
            String serializedMessage = objectMapper.writeValueAsString(message);
            if (serializedMessage.length() > MAX_MESSAGE_CHARS) {
                throw badRequest("A mensagem passa do limite de " + MAX_MESSAGE_CHARS + " caracteres.");
            }
            messages.add(message);
            String serialized = objectMapper.writeValueAsString(messages);
            if (serialized.length() > MAX_CONVERSATION_CHARS) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta conversa ficou longa demais. Inicie uma nova consulta.");
            }
            existing.setMessages(serialized);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Erro ao processar mensagens da conversa", e);
        }
        existing.setUpdatedAt(LocalDateTime.now());
        return knowledgeConversationRepository.save(existing);
    }

    private static String cleanTitle(String title) {
        if (title == null || title.isBlank()) {
            return "Nova Consulta de Conhecimento";
        }
        String t = title.trim();
        return t.length() > MAX_TITLE ? t.substring(0, MAX_TITLE) : t;
    }

    @Transactional
    public void deleteConversation(UUID id, String userId) {
        KnowledgeConversation existing = getConversation(id, userId);
        knowledgeConversationRepository.delete(existing);
    }

    @Transactional(readOnly = true)
    public KnowledgeUserAiSettings getAiSettings(String userId) {
        return knowledgeUserAiSettingsRepository.findById(userId)
                .orElseGet(() -> KnowledgeUserAiSettings.builder().userId(userId).build());
    }

    @Transactional
    public KnowledgeUserAiSettings saveAiSettings(String userId, KnowledgeUserAiSettings updates) {
        KnowledgeUserAiSettings existing = knowledgeUserAiSettingsRepository.findById(userId)
                .orElseGet(() -> KnowledgeUserAiSettings.builder().userId(userId).build());
        if (updates.getModel() != null && updates.getModel().length() > 100) {
            throw badRequest("Modelo inválido.");
        }
        if (updates.getByokApiKey() != null && updates.getByokApiKey().length() > 400) {
            throw badRequest("A chave de API passa do limite de 400 caracteres.");
        }
        if (updates.getModel() != null) {
            existing.setModel(updates.getModel());
        }
        if (updates.getByokApiKey() != null) {
            existing.setByokApiKey(updates.getByokApiKey());
        }
        existing.setUpdatedAt(LocalDateTime.now());
        return knowledgeUserAiSettingsRepository.save(existing);
    }

    @Transactional
    public void incrementTokenUsage(String userId, String userName, long tokens) {
        KnowledgeTokenUsage usage = knowledgeTokenUsageRepository.findById(userId)
                .orElseGet(() -> KnowledgeTokenUsage.builder().userId(userId).totalTokens(0L).build());
        usage.setUserName(userName);
        // O cliente informa o consumo: ignora negativos e limita cada chamada para não distorcer o ranking.
        long safe = Math.max(0L, Math.min(tokens, MAX_TOKENS_PER_CALL));
        usage.setTotalTokens(usage.getTotalTokens() + safe);
        usage.setUpdatedAt(LocalDateTime.now());
        knowledgeTokenUsageRepository.save(usage);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeTokenUsage> getTopTokenUsage() {
        return knowledgeTokenUsageRepository.findTop10ByOrderByTotalTokensDesc();
    }
}
