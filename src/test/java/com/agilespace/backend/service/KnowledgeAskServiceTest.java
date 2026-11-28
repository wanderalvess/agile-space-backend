package com.agilespace.backend.service;

import com.agilespace.backend.dto.KnowledgeAskDtos.AskRequest;
import com.agilespace.backend.dto.KnowledgeAskDtos.AskResponse;
import com.agilespace.backend.dto.KnowledgeSearchDtos.Hit;
import com.agilespace.backend.dto.KnowledgeSearchDtos.SearchRequest;
import com.agilespace.backend.dto.KnowledgeSearchDtos.SearchResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("KnowledgeAskService - contexto citável sem LLM no servidor")
class KnowledgeAskServiceTest {

    @Mock
    private KnowledgeChunkService chunkService;
    @InjectMocks
    private KnowledgeAskService service;

    private static Hit hit(String id, String title, String path, String text, String source, String url) {
        return new Hit(id, "doc-" + id, "api_doc", "ext-" + id, title, path, text, url, source, "pedidos", "v1", 0.03, 1, null);
    }

    @Test
    @DisplayName("monta fontes numeradas, citações e prompt com a pergunta")
    void buildsNumberedContext() {
        when(chunkService.search(any())).thenReturn(new SearchResponse("text", List.of(
                hit("a", "Pedidos API", "Criar", "POST /pedidos cria.", "code", "https://git/p.md"),
                hit("b", "Pedidos TDN", null, "Cadastro de pedido.", "code", null))));

        AskResponse r = service.ask(new AskRequest("Como criar pedido?", null, null, null, null, null, null));

        assertTrue(r.answerable());
        assertFalse(r.mixedSources());
        assertEquals("none", r.provider());
        assertEquals(2, r.citations().size());
        assertEquals(1, r.citations().get(0).n());
        assertEquals("Criar", r.citations().get(0).headingPath());
        assertTrue(r.userPrompt().contains("[1] Pedidos API > Criar (origem: code; módulo: pedidos; versão: v1; https://git/p.md)"));
        assertTrue(r.userPrompt().contains("[2] Pedidos TDN"));
        assertTrue(r.userPrompt().endsWith("PERGUNTA: Como criar pedido?"));
        assertTrue(r.systemPrompt().contains("diverg"));
    }

    @Test
    @DisplayName("código e TDN juntos marcam mixedSources para comparar divergências")
    void mixedSourcesFlag() {
        when(chunkService.search(any())).thenReturn(new SearchResponse("hybrid", List.of(
                hit("a", "API", null, "x", "code", null), hit("b", "TDN", null, "y", "tdn", null))));

        assertTrue(service.ask(new AskRequest("q", null, null, null, null, null, "none")).mixedSources());
    }

    @Test
    @DisplayName("sem trechos: answerable=false e o prompt avisa que não há fontes")
    void noHits() {
        when(chunkService.search(any())).thenReturn(new SearchResponse("text", List.of()));

        AskResponse r = service.ask(new AskRequest("algo", null, null, null, null, null, null));

        assertFalse(r.answerable());
        assertTrue(r.citations().isEmpty());
        assertTrue(r.userPrompt().contains("nenhuma fonte encontrada"));
    }

    @Test
    @DisplayName("contexto respeita o limite de tamanho, mantendo ao menos uma fonte")
    void contextCapped() {
        String big = "x".repeat(5000);
        when(chunkService.search(any())).thenReturn(new SearchResponse("text", List.of(
                hit("a", "A", null, big, "code", null), hit("b", "B", null, big, "code", null),
                hit("c", "C", null, big, "code", null))));

        AskResponse r = service.ask(new AskRequest("q", null, null, null, null, null, null));

        assertEquals(2, r.citations().size());
        assertTrue(r.userPrompt().length() < KnowledgeAskService.MAX_CONTEXT_CHARS + 500);
    }

    @Test
    @DisplayName("repassa filtros e k para a busca")
    void forwardsFilters() {
        when(chunkService.search(any())).thenReturn(new SearchResponse("text", List.of()));

        service.ask(new AskRequest(" pergunta ", null, 3, "api_doc", "code", "pedidos", "NONE"));

        ArgumentCaptor<SearchRequest> cap = ArgumentCaptor.forClass(SearchRequest.class);
        verify(chunkService).search(cap.capture());
        assertEquals("pergunta", cap.getValue().query());
        assertEquals(3, cap.getValue().k());
        assertEquals("api_doc", cap.getValue().docType());
        assertEquals("pedidos", cap.getValue().module());
    }

    @Test
    @DisplayName("pergunta vazia, longa demais ou provider não configurado é 400")
    void validation() {
        assertThrows(ResponseStatusException.class, () -> service.ask(null));
        assertThrows(ResponseStatusException.class,
                () -> service.ask(new AskRequest("  ", null, null, null, null, null, null)));
        assertThrows(ResponseStatusException.class, () -> service.ask(
                new AskRequest("x".repeat(KnowledgeAskService.MAX_QUESTION_CHARS + 1), null, null, null, null, null, null)));
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.ask(new AskRequest("q", null, null, null, null, null, "gemini")));
        assertTrue(e.getReason().contains("gemini"));
        verifyNoInteractions(chunkService);
    }
}
