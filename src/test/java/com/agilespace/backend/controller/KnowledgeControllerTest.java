package com.agilespace.backend.controller;

import com.agilespace.backend.domain.KnowledgeDocument;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.KnowledgeService;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    private HttpServletRequest mockRequest(String userId, String role) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn(userId);
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE)).thenReturn(role);
        return request;
    }

    @Test
    public void testSaveOrUpdateDocumentAsAdmin() {
        KnowledgeDocument doc = new KnowledgeDocument();
        when(service.saveOrUpdateDocument(doc)).thenReturn(doc);

        ResponseEntity<KnowledgeDocument> response = controller.saveOrUpdateDocument(doc, mockRequest("admin-boss", "ADMIN"));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("admin-boss", doc.getAuthorId());
    }

    @Test
    public void testSaveOrUpdateDocumentRejectsNonAdmin() {
        KnowledgeDocument doc = new KnowledgeDocument();

        assertThrows(ResponseStatusException.class,
                () -> controller.saveOrUpdateDocument(doc, mockRequest("u1", "MEMBER")));
        verify(service, never()).saveOrUpdateDocument(any());
    }

    @Test
    public void testDeleteDocumentAsAdmin() {
        UUID id = UUID.randomUUID();
        when(service.deleteDocument(id, "admin-boss")).thenReturn(new KnowledgeDocument());

        ResponseEntity<Void> response = controller.deleteDocument(id, mockRequest("admin-boss", "ADMIN"));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    public void testDeleteDocumentRejectsNonAdmin() {
        UUID id = UUID.randomUUID();

        assertThrows(ResponseStatusException.class,
                () -> controller.deleteDocument(id, mockRequest("u1", "MEMBER")));
        verify(service, never()).deleteDocument(any(), any());
    }
}
