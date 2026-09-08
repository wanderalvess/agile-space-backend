package com.agilespace.backend.service;

import com.agilespace.backend.domain.KnowledgeDocument;
import com.agilespace.backend.repository.KnowledgeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("KnowledgeService - Base de Conhecimento e Integração com TDN")
class KnowledgeServiceTest {

    @Mock
    private KnowledgeRepository repository;

    @InjectMocks
    private KnowledgeService service;

    @Nested
    @DisplayName("Consulta e Listagem de Artigos")
    class SearchAndListTests {

        @Test
        @DisplayName("Deve listar documentos ativos excluindo registros deletados quando sem filtros")
        void shouldListActiveDocumentsWithoutFilter() {
            Page<KnowledgeDocument> page = new PageImpl<>(Collections.singletonList(new KnowledgeDocument()));
            when(repository.findByStatusNot(eq("deleted"), any(Pageable.class))).thenReturn(page);

            Page<KnowledgeDocument> result = service.listDocuments(null, null, PageRequest.of(0, 10));

            assertEquals(1, result.getTotalElements());
            verify(repository).findByStatusNot(eq("deleted"), any(Pageable.class));
        }

        @Test
        @DisplayName("Deve buscar e filtrar por termos e tags")
        void shouldFilterByQueryAndTags() {
            KnowledgeDocument doc = KnowledgeDocument.builder()
                    .tags(new HashSet<>(Arrays.asList("react", "architecture")))
                    .build();
            Page<KnowledgeDocument> page = new PageImpl<>(Collections.singletonList(doc));
            when(repository.searchActive(eq("arquitetura"), eq("deleted"), any(Pageable.class))).thenReturn(page);

            Set<String> tags = Collections.singleton("react");
            Page<KnowledgeDocument> result = service.listDocuments("arquitetura", tags, PageRequest.of(0, 10));

            assertEquals(1, result.getTotalElements());
        }

        @Test
        @DisplayName("Deve buscar documento por ID")
        void shouldGetDocumentById() {
            UUID docId = UUID.randomUUID();
            KnowledgeDocument doc = KnowledgeDocument.builder().id(docId).title("Guia de Boas Práticas").build();
            when(repository.findById(docId)).thenReturn(Optional.of(doc));

            KnowledgeDocument result = service.getDocumentById(docId);

            assertNotNull(result);
            assertEquals("Guia de Boas Práticas", result.getTitle());
        }
    }

    @Nested
    @DisplayName("Criação, Atualização e Soft Delete")
    class MutationTests {

        @Test
        @DisplayName("Deve publicar novo documento com status padrão 'published'")
        void shouldSaveNewDocumentAsPublished() {
            KnowledgeDocument doc = KnowledgeDocument.builder().title("Manual Agile Space").build();
            when(repository.save(any(KnowledgeDocument.class))).thenAnswer(i -> i.getArgument(0));

            KnowledgeDocument saved = service.saveOrUpdateDocument(doc);

            assertEquals("published", saved.getStatus());
            verify(repository).save(doc);
        }

        @Test
        @DisplayName("Deve realizar soft-delete gravando autor e data de exclusão")
        void shouldSoftDeleteDocument() {
            UUID docId = UUID.randomUUID();
            KnowledgeDocument existing = KnowledgeDocument.builder().id(docId).status("published").build();

            when(repository.findById(docId)).thenReturn(Optional.of(existing));
            when(repository.save(any(KnowledgeDocument.class))).thenAnswer(i -> i.getArgument(0));

            KnowledgeDocument deleted = service.deleteDocument(docId, "user-admin");

            assertEquals("deleted", deleted.getStatus());
            assertEquals("user-admin", deleted.getDeletedBy());
            assertNotNull(deleted.getDeletedAt());
        }

        @Test
        @DisplayName("Deve incrementar contador de visualizações do documento")
        void shouldIncrementViews() {
            UUID docId = UUID.randomUUID();
            KnowledgeDocument existing = KnowledgeDocument.builder().id(docId).views(10).build();

            when(repository.findById(docId)).thenReturn(Optional.of(existing));
            when(repository.save(any(KnowledgeDocument.class))).thenAnswer(i -> i.getArgument(0));

            KnowledgeDocument result = service.incrementViews(docId);

            assertEquals(11, result.getViews());
        }
    }
}
