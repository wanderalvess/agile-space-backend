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
    public void testSaveOrUpdateDocumentSetsAuthorFromTokenForAnyAuthenticated() {
        // Criar/editar é aberto a qualquer autenticado — não precisa ser ADMIN.
        KnowledgeDocument doc = new KnowledgeDocument();
        when(service.saveOrUpdateDocument(doc)).thenReturn(doc);

        ResponseEntity<KnowledgeDocument> response = controller.saveOrUpdateDocument(doc, mockRequest("u1", "MEMBER"));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("u1", doc.getAuthorId(), "authorId deve vir do token, nunca do corpo");
    }

    @Test
    public void testUpdateDocumentSetsUpdatedByFromTokenForAnyAuthenticated() {
        UUID id = UUID.randomUUID();
        KnowledgeDocument doc = new KnowledgeDocument();
        when(service.updateDocument(eq(id), any())).thenReturn(doc);

        ResponseEntity<KnowledgeDocument> response = controller.updateDocument(id, doc, mockRequest("u1", "MEMBER"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("u1", doc.getUpdatedBy());
    }

    @Test
    public void testDeleteDocumentAllowsOwnerNonAdmin() {
        UUID id = UUID.randomUUID();
        when(service.deleteDocument(id, "u1", false)).thenReturn(new KnowledgeDocument());

        ResponseEntity<Void> response = controller.deleteDocument(id, mockRequest("u1", "MEMBER"));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(service).deleteDocument(id, "u1", false);
    }

    @Test
    public void testDeleteDocumentAsAdminPassesAdminFlag() {
        UUID id = UUID.randomUUID();
        when(service.deleteDocument(id, "admin-boss", true)).thenReturn(new KnowledgeDocument());

        ResponseEntity<Void> response = controller.deleteDocument(id, mockRequest("admin-boss", "ADMIN"));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(service).deleteDocument(id, "admin-boss", true);
    }

    @Test
    public void testDeleteDocumentPropagatesForbiddenFromServiceForNonOwnerNonAdmin() {
        UUID id = UUID.randomUUID();
        when(service.deleteDocument(id, "intruso", false))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Apenas o autor ou um administrador pode apagar este documento."));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteDocument(id, mockRequest("intruso", "MEMBER")));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }
}
