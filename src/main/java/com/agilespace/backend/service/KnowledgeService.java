package com.agilespace.backend.service;

import com.agilespace.backend.domain.KnowledgeConversation;
import com.agilespace.backend.domain.KnowledgeDocument;
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
        validateDocument(doc);
        // Documento novo nunca herda id/contadores/lixeira vindos do corpo: um POST com o id de um
        // documento existente virava "merge" e sobrescrevia (inclusive a autoria) o de outra pessoa.
        doc.setId(null);
        doc.setViews(0);
        doc.setDeletedAt(null);
        doc.setDeletedBy(null);
        if ("deleted".equals(doc.getStatus())) {
            throw badRequest("Para apagar um documento use a exclusão (lixeira).");
        }
        // Se for uma importação do TDN, verifica se o documento com esse tdnId já existe
        if (doc.getTdnId() != null && !doc.getTdnId().trim().isEmpty()) {
            Optional<KnowledgeDocument> existingOpt = knowledgeRepository.findByTdnId(doc.getTdnId());
            if (existingOpt.isPresent()) {
                KnowledgeDocument existing = existingOpt.get();
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
                }
                return knowledgeRepository.save(existing);
            }
        }

        // Se for novo
        if (doc.getStatus() == null) {
            doc.setStatus("published");
        }
        return knowledgeRepository.save(doc);
    }

    /** Mantido para chamadas internas sem contexto de usuário: não permite apagar via status. */
    @Transactional
    public KnowledgeDocument updateDocument(UUID id, KnowledgeDocument updatedDoc) {
        return updateDocument(id, updatedDoc, updatedDoc.getUpdatedBy(), false);
    }

    @Transactional
    public KnowledgeDocument updateDocument(UUID id, KnowledgeDocument updatedDoc, String callerId, boolean isAdmin) {
        validateDocument(updatedDoc);
        KnowledgeDocument existing = getDocumentById(id);
        String newStatus = updatedDoc.getStatus() != null ? updatedDoc.getStatus() : existing.getStatus();
        boolean deleting = "deleted".equals(newStatus) && !"deleted".equals(existing.getStatus());
        if (deleting && !isAdmin && (callerId == null || !callerId.equals(existing.getAuthorId()))) {
            // Mudar o status para "deleted" pelo PUT contornava a regra "só autor ou admin apaga".
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Apenas o autor ou um administrador pode apagar este documento.");
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
        existing.setUpdatedBy(updatedDoc.getUpdatedBy());
        if (updatedDoc.getEmbedding() != null) {
            existing.setEmbedding(updatedDoc.getEmbedding());
        }
        return knowledgeRepository.save(existing);
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
        if (!isAdmin && !callerId.equals(doc.getAuthorId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Apenas o autor ou um administrador pode apagar este documento.");
        }
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
