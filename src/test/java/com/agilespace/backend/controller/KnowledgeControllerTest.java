package com.agilespace.backend.controller;

import com.agilespace.backend.domain.KnowledgeDocument;
import com.agilespace.backend.service.KnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class KnowledgeControllerTest {

    @Mock
    private KnowledgeService service;

    @InjectMocks
    private KnowledgeController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testListDocuments() {
        Page<KnowledgeDocument> page = new PageImpl<>(Arrays.asList(new KnowledgeDocument()));
        when(service.listDocuments(eq("query"), any(), any(PageRequest.class))).thenReturn(page);
        
        ResponseEntity<Page<KnowledgeDocument>> response = controller.listDocuments("query", null, PageRequest.of(0, 20));
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testGetDocumentByIdFound() {
        UUID id = UUID.randomUUID();
        when(service.getDocumentById(id)).thenReturn(new KnowledgeDocument());
        
        ResponseEntity<KnowledgeDocument> response = controller.getDocumentById(id);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testSaveOrUpdateDocument() {
        KnowledgeDocument doc = new KnowledgeDocument();
        when(service.saveOrUpdateDocument(doc)).thenReturn(doc);
        
        ResponseEntity<KnowledgeDocument> response = controller.saveOrUpdateDocument(doc);
        
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
    }

    @Test
    public void testDeleteDocument() {
        UUID id = UUID.randomUUID();
        when(service.deleteDocument(id, "admin")).thenReturn(new KnowledgeDocument());
        
        ResponseEntity<Void> response = controller.deleteDocument(id, "admin");
        
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }
}
