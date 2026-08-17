package com.agilespace.backend;

import com.agilespace.backend.domain.KnowledgeDocument;
import com.agilespace.backend.repository.KnowledgeRepository;
import com.agilespace.backend.service.KnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class KnowledgeServiceTest {

    @Mock
    private KnowledgeRepository repository;

    @InjectMocks
    private KnowledgeService service;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testListDocumentsNoQueryNoTags() {
        Page<KnowledgeDocument> page = new PageImpl<>(Arrays.asList(new KnowledgeDocument()));
        when(repository.findByStatusNot(eq("deleted"), any(Pageable.class))).thenReturn(page);
        
        Page<KnowledgeDocument> result = service.listDocuments(null, null, PageRequest.of(0, 10));
        assertEquals(1, result.getTotalElements());
    }

    @Test
    public void testListDocumentsWithQueryAndTags() {
        KnowledgeDocument doc = KnowledgeDocument.builder().tags(new HashSet<>(Arrays.asList("java", "spring"))).build();
        Page<KnowledgeDocument> page = new PageImpl<>(Arrays.asList(doc));
        when(repository.searchActive(eq("test"), eq("deleted"), any(Pageable.class))).thenReturn(page);
        
        Set<String> tags = new HashSet<>(Arrays.asList("java"));
        Page<KnowledgeDocument> result = service.listDocuments("test", tags, PageRequest.of(0, 10));
        assertEquals(1, result.getTotalElements());
    }

    @Test
    public void testGetDocumentById() {
        UUID docId = UUID.randomUUID();
        KnowledgeDocument doc = KnowledgeDocument.builder().id(docId).title("Doc1").build();
        when(repository.findById(docId)).thenReturn(Optional.of(doc));

        KnowledgeDocument result = service.getDocumentById(docId);
        assertEquals("Doc1", result.getTitle());
    }

    @Test
    public void testGetDocumentByIdNotFound() {
        UUID docId = UUID.randomUUID();
        when(repository.findById(docId)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.getDocumentById(docId));
    }

    @Test
    public void testSaveDocumentNew() {
        KnowledgeDocument doc = KnowledgeDocument.builder().title("New").build();
        when(repository.save(any(KnowledgeDocument.class))).thenAnswer(i -> i.getArgument(0));

        KnowledgeDocument saved = service.saveOrUpdateDocument(doc);
        assertEquals("published", saved.getStatus());
        verify(repository).save(doc);
    }

    @Test
    public void testSaveDocumentExistingTdnId() {
        KnowledgeDocument doc = KnowledgeDocument.builder().tdnId("tdn-1").title("Updated Tdn").build();
        KnowledgeDocument existing = KnowledgeDocument.builder().tdnId("tdn-1").title("Old Tdn").build();
        
        when(repository.findByTdnId("tdn-1")).thenReturn(Optional.of(existing));
        when(repository.save(any(KnowledgeDocument.class))).thenAnswer(i -> i.getArgument(0));

        KnowledgeDocument saved = service.saveOrUpdateDocument(doc);
        assertEquals("Updated Tdn", saved.getTitle());
        verify(repository).save(existing);
    }

    @Test
    public void testUpdateDocument() {
        UUID docId = UUID.randomUUID();
        KnowledgeDocument existing = KnowledgeDocument.builder().id(docId).title("Old").build();
        KnowledgeDocument update = KnowledgeDocument.builder().title("New").build();
        
        when(repository.findById(docId)).thenReturn(Optional.of(existing));
        when(repository.save(any(KnowledgeDocument.class))).thenAnswer(i -> i.getArgument(0));

        KnowledgeDocument updated = service.updateDocument(docId, update);
        assertEquals("New", updated.getTitle());
    }

    @Test
    public void testDeleteDocument() {
        UUID docId = UUID.randomUUID();
        KnowledgeDocument existing = KnowledgeDocument.builder().id(docId).status("published").build();
        
        when(repository.findById(docId)).thenReturn(Optional.of(existing));
        when(repository.save(any(KnowledgeDocument.class))).thenAnswer(i -> i.getArgument(0));

        KnowledgeDocument deleted = service.deleteDocument(docId, "user-1");
        assertEquals("deleted", deleted.getStatus());
        assertEquals("user-1", deleted.getDeletedBy());
        assertNotNull(deleted.getDeletedAt());
    }

    @Test
    public void testIncrementViews() {
        UUID docId = UUID.randomUUID();
        KnowledgeDocument existing = KnowledgeDocument.builder().id(docId).views(5).build();
        
        when(repository.findById(docId)).thenReturn(Optional.of(existing));
        when(repository.save(any(KnowledgeDocument.class))).thenAnswer(i -> i.getArgument(0));

        KnowledgeDocument incremented = service.incrementViews(docId);
        assertEquals(6, incremented.getViews());
    }
}
