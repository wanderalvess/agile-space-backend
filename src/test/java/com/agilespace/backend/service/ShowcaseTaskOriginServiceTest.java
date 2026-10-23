package com.agilespace.backend.service;

import com.agilespace.backend.domain.ShowcaseTask;
import com.agilespace.backend.domain.ShowcaseTaskOrigin;
import com.agilespace.backend.dto.ShowcaseFieldOriginDto;
import com.agilespace.backend.repository.ShowcaseTaskOriginRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ShowcaseTaskOriginService - origem dos textos importados do Jira")
class ShowcaseTaskOriginServiceTest {

    @Mock
    private ShowcaseTaskOriginRepository repository;

    @InjectMocks
    private ShowcaseTaskOriginService service;

    private static ShowcaseFieldOriginDto dto(String field, String kind, String label, String text) {
        return new ShowcaseFieldOriginDto(field, kind, label, text, null, null);
    }

    @Test
    @DisplayName("Grava a origem de cada campo com quem importou e quando, trocando as anteriores do card")
    @SuppressWarnings("unchecked")
    void replaceStoresAndStamps() {
        ShowcaseTaskOrigin old = ShowcaseTaskOrigin.builder().id("o1").sessionId("s1").taskId("t1").field("problem").build();
        when(repository.findBySessionIdAndTaskId("s1", "t1")).thenReturn(List.of(old));

        service.replace("s1", "t1", List.of(
                dto("problem", "descricao", "Descrição, início do texto", "Texto do problema"),
                dto("solution", "comentario", "Comentário de Ana, 12/10", "Texto da solução")), "user-1");

        verify(repository).deleteAll(List.of(old));
        ArgumentCaptor<List<ShowcaseTaskOrigin>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        assertEquals(2, saved.getValue().size());
        ShowcaseTaskOrigin first = saved.getValue().get(0);
        assertEquals("s1", first.getSessionId());
        assertEquals("t1", first.getTaskId());
        assertEquals("user-1", first.getImportedBy());
        assertNotNull(first.getImportedAt());
        assertNotNull(first.getId());
    }

    @Test
    @DisplayName("Origens iguais às gravadas não mexem em nada (nem recarimbam quem importou)")
    void sameContentIsANoOp() {
        ShowcaseTaskOrigin stored = ShowcaseTaskOrigin.builder().id("o1").sessionId("s1").taskId("t1").field("problem")
                .sourceKind("descricao").sourceLabel("Descrição, início do texto").originalText("Texto do Jira")
                .importedBy("quem-importou").importedAt(LocalDateTime.of(2026, 10, 20, 9, 0)).build();
        when(repository.findBySessionIdAndTaskId("s1", "t1")).thenReturn(List.of(stored));

        service.replace("s1", "t1", List.of(dto("problem", "descricao", "Descrição, início do texto", "Texto do Jira")), "outra-pessoa");

        verify(repository, never()).deleteAll(any());
        verify(repository, never()).saveAll(any());
    }

    @Test
    @DisplayName("Origem com texto diferente do gravado troca a anterior")
    void changedContentReplaces() {
        ShowcaseTaskOrigin stored = ShowcaseTaskOrigin.builder().id("o1").sessionId("s1").taskId("t1").field("problem")
                .sourceKind("descricao").sourceLabel("Descrição, início do texto").originalText("Texto antigo").build();
        when(repository.findBySessionIdAndTaskId("s1", "t1")).thenReturn(List.of(stored));

        service.replace("s1", "t1", List.of(dto("problem", "descricao", "Descrição, início do texto", "Texto novo")), "u");

        verify(repository).deleteAll(List.of(stored));
        verify(repository).saveAll(any());
    }

    @Test
    @DisplayName("Lista vazia apaga as origens do card sem gravar nada novo")
    void emptyListClears() {
        ShowcaseTaskOrigin stored = ShowcaseTaskOrigin.builder().id("o1").sessionId("s1").taskId("t1").field("problem")
                .sourceKind("campo").sourceLabel("x").originalText("texto").build();
        when(repository.findBySessionIdAndTaskId("s1", "t1")).thenReturn(List.of(stored));

        service.replace("s1", "t1", List.of(), "user-1");

        verify(repository).deleteAll(any());
        verify(repository, never()).saveAll(any());
    }

    @Test
    @DisplayName("Campo, tipo de fonte ou texto inválido é 400 e nada é gravado")
    void invalidIsRejected() {
        assertThrows(ResponseStatusException.class,
                () -> service.replace("s1", "t1", List.of(dto("title", "campo", "x", "texto")), "u"));
        assertThrows(ResponseStatusException.class,
                () -> service.replace("s1", "t1", List.of(dto("problem", "wiki", "x", "texto")), "u"));
        assertThrows(ResponseStatusException.class,
                () -> service.replace("s1", "t1", List.of(dto("problem", "campo", "x", "   ")), "u"));
        verify(repository, never()).saveAll(any());
        verify(repository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("Mais de uma origem para o mesmo campo guarda só a última")
    @SuppressWarnings("unchecked")
    void sameFieldKeepsLast() {
        service.replace("s1", "t1", List.of(
                dto("problem", "campo", "A", "primeiro"),
                dto("problem", "descricao", "B", "segundo")), "u");

        ArgumentCaptor<List<ShowcaseTaskOrigin>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        assertEquals(1, saved.getValue().size());
        assertEquals("segundo", saved.getValue().get(0).getOriginalText());
    }

    @Test
    @DisplayName("Texto e rótulo muito longos são cortados")
    @SuppressWarnings("unchecked")
    void longValuesAreTruncated() {
        service.replace("s1", "t1", List.of(
                dto("problem", "campo", "r".repeat(1000), "t".repeat(ShowcaseTaskOriginService.MAX_TEXT + 50))), "u");

        ArgumentCaptor<List<ShowcaseTaskOrigin>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        assertEquals(ShowcaseTaskOriginService.MAX_LABEL, saved.getValue().get(0).getSourceLabel().length());
        assertEquals(ShowcaseTaskOriginService.MAX_TEXT, saved.getValue().get(0).getOriginalText().length());
    }

    @Test
    @DisplayName("attach põe as origens em cada card e lista vazia nos que não têm")
    void attachFillsEachCard() {
        ShowcaseTaskOrigin o = ShowcaseTaskOrigin.builder().id("o1").sessionId("s1").taskId("t1").field("problem")
                .sourceKind("campo").sourceLabel("Campo do Jira").originalText("Texto").importedBy("u")
                .importedAt(LocalDateTime.of(2026, 10, 22, 10, 0)).build();
        when(repository.findBySessionId("s1")).thenReturn(List.of(o));
        ShowcaseTask a = ShowcaseTask.builder().id("t1").build();
        ShowcaseTask b = ShowcaseTask.builder().id("t2").build();

        service.attach("s1", List.of(a, b));

        assertEquals(1, a.getOrigins().size());
        assertEquals("problem", a.getOrigins().get(0).field());
        assertEquals("Texto", a.getOrigins().get(0).originalText());
        assertTrue(b.getOrigins().isEmpty());
    }

    @Test
    @DisplayName("Card que saiu da Review leva as origens junto; os outros ficam")
    @SuppressWarnings("unchecked")
    void orphansAreRemoved() {
        ShowcaseTaskOrigin keep = ShowcaseTaskOrigin.builder().id("o1").taskId("t1").build();
        ShowcaseTaskOrigin gone = ShowcaseTaskOrigin.builder().id("o2").taskId("t2").build();
        when(repository.findBySessionId("s1")).thenReturn(List.of(keep, gone));

        service.removeOrphans("s1", Set.of("t1"));

        ArgumentCaptor<List<ShowcaseTaskOrigin>> removed = ArgumentCaptor.forClass(List.class);
        verify(repository).deleteAll(removed.capture());
        assertEquals(List.of(gone), removed.getValue());
    }
}
