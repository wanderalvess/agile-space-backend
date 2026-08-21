package com.agilespace.backend.service;

import com.agilespace.backend.domain.KnowledgeDocument;
import com.agilespace.backend.repository.KnowledgeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final KnowledgeRepository knowledgeRepository;

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "o", "as", "os", "um", "uma", "uns", "umas",
            "de", "do", "da", "dos", "das", "em", "no", "na", "nos", "nas",
            "por", "pelo", "pela", "pelos", "pelas", "para", "pra", "com", "sem",
            "qual", "quais", "quem", "como", "onde", "quando", "porque", "que",
            "este", "esta", "esse", "essa", "aquele", "aquela", "tem", "temos", "e", "ou"
    );

    @Transactional(readOnly = true)
    public Page<KnowledgeDocument> listDocuments(String query, Set<String> tags, Pageable pageable) {
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
    public KnowledgeDocument getDocumentById(UUID id) {
        return knowledgeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Document not found with id: " + id));
    }

    @Transactional
    public KnowledgeDocument saveOrUpdateDocument(KnowledgeDocument doc) {
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
                return knowledgeRepository.save(existing);
            }
        }

        // Se for novo
        if (doc.getStatus() == null) {
            doc.setStatus("published");
        }
        return knowledgeRepository.save(doc);
    }

    @Transactional
    public KnowledgeDocument updateDocument(UUID id, KnowledgeDocument updatedDoc) {
        KnowledgeDocument existing = getDocumentById(id);
        existing.setTitle(updatedDoc.getTitle());
        existing.setContent(updatedDoc.getContent());
        existing.setCategory(updatedDoc.getCategory());
        existing.setFullPath(updatedDoc.getFullPath());
        existing.setModuleId(updatedDoc.getModuleId());
        existing.setModuleName(updatedDoc.getModuleName());
        existing.setFolderId(updatedDoc.getFolderId());
        existing.setFolderName(updatedDoc.getFolderName());
        existing.setStatus(updatedDoc.getStatus());
        existing.setTags(updatedDoc.getTags());
        existing.setByteSize(updatedDoc.getByteSize());
        existing.setUpdatedBy(updatedDoc.getUpdatedBy());
        return knowledgeRepository.save(existing);
    }

    @Transactional
    public KnowledgeDocument deleteDocument(UUID id, String deletedBy) {
        KnowledgeDocument doc = getDocumentById(id);
        doc.setStatus("deleted");
        doc.setDeletedAt(LocalDateTime.now());
        doc.setDeletedBy(deletedBy);
        return knowledgeRepository.save(doc);
    }

    @Transactional
    public KnowledgeDocument incrementViews(UUID id) {
        KnowledgeDocument doc = getDocumentById(id);
        doc.setViews(doc.getViews() + 1);
        return knowledgeRepository.save(doc);
    }
}
