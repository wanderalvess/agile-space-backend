package com.agilespace.backend.controller;

import com.agilespace.backend.domain.KnowledgeDocument;
import com.agilespace.backend.repository.KnowledgeTokenUsageRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.ApiKeyAuthenticationFilter;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.KnowledgeService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("Controllers de conhecimento - corpo mal formado vira 400, não 500")
class KnowledgeControllerHardeningTest {

    private final KnowledgeService service = mock(KnowledgeService.class);
    private final KnowledgeController controller =
            new KnowledgeController(service, mock(KnowledgeTokenUsageRepository.class), mock(UserRepository.class));
    private final KnowledgeApiV1Controller v1 = new KnowledgeApiV1Controller(service);

    private HttpServletRequest user(String id) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn(id);
        return r;
    }

    private HttpServletRequest apiKey() {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_GRANDFATHERED)).thenReturn(Boolean.TRUE);
        return r;
    }

    @Test
    @DisplayName("Busca semântica aceita embedding com inteiros e recusa itens que não são número")
    void semanticAcceptsIntegers() {
        when(service.semanticSearch(any(float[].class), any())).thenReturn(new PageImpl<>(List.of()));

        assertEquals(HttpStatus.OK, controller.semanticSearch(Map.of("embedding", List.of(0, 1, 0.5)), PageRequest.of(0, 10)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.semanticSearch(Map.of("embedding", List.of("a")), PageRequest.of(0, 10)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.semanticSearch(Map.of("embedding", "texto"), PageRequest.of(0, 10)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.semanticSearch(Map.of(), PageRequest.of(0, 10)).getStatusCode());
    }

    @Test
    @DisplayName("Contagem de tokens com valor não numérico não estoura (conta como 0)")
    void tokensNonNumeric() {
        controller.incrementTokenUsage(Map.of("tokens", "muitos"), user("u1"));
        verify(service).incrementTokenUsage(anyString(), anyString(), eq(0L));
    }

    @Test
    @DisplayName("API v1 não serve documento da lixeira")
    void v1HidesDeleted() {
        UUID id = UUID.randomUUID();
        when(service.getDocumentById(id)).thenReturn(KnowledgeDocument.builder().id(id).title("x").status("deleted").authorId("a").build());

        assertEquals(HttpStatus.NOT_FOUND, v1.getDocumentById(id, apiKey()).getStatusCode());

        when(service.getDocumentById(id)).thenReturn(KnowledgeDocument.builder().id(id).title("x").status("published").authorId("a").build());
        assertEquals(HttpStatus.OK, v1.getDocumentById(id, apiKey()).getStatusCode());
    }

    @Test
    @DisplayName("API v1 POST com título/conteúdo que não é texto é 400")
    void v1RejectsNonStringFields() {
        assertEquals(HttpStatus.BAD_REQUEST, v1.createDocument(Map.of("title", 123), apiKey()).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, v1.createDocument(Map.of("title", "ok", "content", 5), apiKey()).getStatusCode());
        verify(service, never()).saveOrUpdateDocument(any());
    }

    @Test
    @DisplayName("API v1 POST válido cria com autor da chave")
    void v1Creates() {
        when(service.saveOrUpdateDocument(any(KnowledgeDocument.class))).thenAnswer(i -> i.getArgument(0));
        ResponseEntity<KnowledgeDocument> res = v1.createDocument(Map.of("title", " Guia ", "content", "oi"), apiKey());
        assertEquals(HttpStatus.CREATED, res.getStatusCode());
        assertEquals("mcp-server", res.getBody().getAuthorId());
    }
}
