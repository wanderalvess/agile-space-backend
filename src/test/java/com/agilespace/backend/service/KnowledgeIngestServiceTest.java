package com.agilespace.backend.service;

import com.agilespace.backend.domain.KnowledgeDocRelation;
import com.agilespace.backend.domain.KnowledgeDocument;
import com.agilespace.backend.dto.KnowledgeIngestDtos.Item;
import com.agilespace.backend.dto.KnowledgeIngestDtos.Response;
import com.agilespace.backend.repository.KnowledgeDocRelationRepository;
import com.agilespace.backend.repository.KnowledgeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("KnowledgeIngestService - ingestão em lote por id estável")
class KnowledgeIngestServiceTest {

    @Mock
    private KnowledgeRepository repository;
    @Mock
    private KnowledgeDocRelationRepository relationRepository;
    @Mock
    private KnowledgeService knowledgeService;
    @InjectMocks
    private KnowledgeIngestService service;

    private static Item item(String type, String ext, String title, String content) {
        return new Item(type, ext, title, content, "code", "https://git/x", "pedidos", "v1.0", "API",
                List.of("java"), null, null, null);
    }

    private KnowledgeDocument saved(UUID id) {
        return KnowledgeDocument.builder().id(id).title("t").authorId("u").status("published").build();
    }

    @Test
    @DisplayName("documento novo: cria com campos de origem, hash e versão inicial")
    void createsNewDocument() {
        when(repository.findByDocTypeAndExternalId("api_doc", "p:Foo.md")).thenReturn(Optional.empty());
        UUID id = UUID.randomUUID();
        when(repository.save(any(KnowledgeDocument.class))).thenAnswer(inv -> {
            KnowledgeDocument d = inv.getArgument(0);
            d.setId(id);
            return d;
        });

        Response r = service.ingest(List.of(item("api_doc", "p:Foo.md", "Foo", "corpo")), "u1");

        assertEquals(1, r.created());
        assertEquals("created", r.results().get(0).status());
        ArgumentCaptor<KnowledgeDocument> cap = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(repository).save(cap.capture());
        KnowledgeDocument d = cap.getValue();
        assertEquals("api_doc", d.getDocType());
        assertEquals("p:Foo.md", d.getExternalId());
        assertEquals("code", d.getSource());
        assertEquals("https://git/x", d.getSourceUrl());
        assertEquals("v1.0", d.getDocVersion());
        assertEquals("pedidos", d.getModuleName());
        assertEquals("published", d.getStatus());
        assertEquals("shared", d.getVisibility());
        assertEquals(64, d.getContentHash().length());
        verify(knowledgeService).recordVersion(eq(d), eq("u1"), any());
    }

    @Test
    @DisplayName("mesmo conteúdo: unchanged, sem regravar nem criar versão")
    void unchangedWhenHashMatches() {
        String hash = KnowledgeIngestService.hash("api_doc", "Foo", "corpo", "API", "code", "https://git/x", "pedidos",
                "v1.0", Set.of("java"), "shared");
        UUID id = UUID.randomUUID();
        KnowledgeDocument existing = saved(id);
        existing.setContentHash(hash);
        when(repository.findByDocTypeAndExternalId("api_doc", "p:Foo.md")).thenReturn(Optional.of(existing));

        Response r = service.ingest(List.of(item("api_doc", "p:Foo.md", "Foo", "corpo")), "u1");

        assertEquals(1, r.unchanged());
        assertEquals("unchanged", r.results().get(0).status());
        verify(repository, never()).save(any());
        verify(knowledgeService, never()).recordVersion(any(), any(), any());
    }

    @Test
    @DisplayName("conteúdo mudou: atualiza, descarta embedding antigo e registra versão")
    void updatesWhenChanged() {
        UUID id = UUID.randomUUID();
        KnowledgeDocument existing = saved(id);
        existing.setContentHash("antigo");
        existing.setEmbedding(new float[]{0.1f});
        when(repository.findByDocTypeAndExternalId("api_doc", "p:Foo.md")).thenReturn(Optional.of(existing));
        when(knowledgeService.contentChanged(any(), any())).thenReturn(true);
        when(repository.save(any(KnowledgeDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        Response r = service.ingest(List.of(item("api_doc", "p:Foo.md", "Foo", "novo corpo")), "u1");

        assertEquals(1, r.updated());
        assertNull(existing.getEmbedding());
        assertEquals("novo corpo", existing.getContent());
        verify(knowledgeService).ensureBaseline(existing);
        verify(knowledgeService).recordVersion(eq(existing), eq("u1"), any());
    }

    @Test
    @DisplayName("documento da lixeira volta a publicado ao ser reenviado")
    void revivesDeletedDocument() {
        KnowledgeDocument existing = saved(UUID.randomUUID());
        existing.setStatus("deleted");
        existing.setContentHash("qualquer");
        when(repository.findByDocTypeAndExternalId("skill", "s1")).thenReturn(Optional.of(existing));
        when(repository.save(any(KnowledgeDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        Response r = service.ingest(List.of(item("skill", "s1", "S", "x")), "u1");

        assertEquals("updated", r.results().get(0).status());
        assertEquals("published", existing.getStatus());
        assertNull(existing.getDeletedAt());
    }

    @Test
    @DisplayName("item inválido vira erro no resultado e não derruba o lote")
    void invalidItemDoesNotFailBatch() {
        when(repository.findByDocTypeAndExternalId("prompt", "ok")).thenReturn(Optional.empty());
        when(repository.save(any(KnowledgeDocument.class))).thenAnswer(inv -> {
            KnowledgeDocument d = inv.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });

        Response r = service.ingest(List.of(
                item("tipo_errado", "a", "A", "x"),
                item("prompt", "", "B", "x"),
                new Item("prompt", "c", "C", "x", "git", null, null, null, null, null, null, null, null),
                new Item("prompt", "d", "D", "x", null, null, null, null, null, null, null, "restricted", null),
                item("prompt", "ok", "OK", "x")), "u1");

        assertEquals(4, r.errors());
        assertEquals(1, r.created());
        assertEquals("error", r.results().get(0).status());
        assertTrue(r.results().get(3).message().contains("visibility"));
    }

    @Test
    @DisplayName("lote vazio ou acima de 50 itens é 400")
    void batchLimits() {
        assertThrows(ResponseStatusException.class, () -> service.ingest(List.of(), "u"));
        assertThrows(ResponseStatusException.class, () -> service.ingest(null, "u"));
        List<Item> big = new ArrayList<>();
        for (int i = 0; i < KnowledgeIngestService.MAX_BATCH + 1; i++) {
            big.add(item("prompt", "p" + i, "T", "x"));
        }
        assertThrows(ResponseStatusException.class, () -> service.ingest(big, "u"));
    }

    @Test
    @DisplayName("related: grava as ligações existentes e avisa as que não existem")
    void relationsResolvedAndMissingReported() {
        UUID docId = UUID.randomUUID();
        UUID tdnId = UUID.randomUUID();
        KnowledgeDocument tdn = saved(tdnId);
        when(repository.findByDocTypeAndExternalId("api_doc", "p:Foo.md")).thenReturn(Optional.empty());
        when(repository.findByDocTypeAndExternalId("tdn_page", "123")).thenReturn(Optional.of(tdn));
        when(repository.findByDocTypeAndExternalId("tdn_page", "999")).thenReturn(Optional.empty());
        when(repository.save(any(KnowledgeDocument.class))).thenAnswer(inv -> {
            KnowledgeDocument d = inv.getArgument(0);
            d.setId(docId);
            return d;
        });
        Item withRelated = new Item("api_doc", "p:Foo.md", "Foo", "x", "code", null, null, null, null, null,
                List.of("tdn_page:123", "tdn_page:999", "sem-dois-pontos"), null, null);

        Response r = service.ingest(List.of(withRelated), "u1");

        ArgumentCaptor<KnowledgeDocRelation> cap = ArgumentCaptor.forClass(KnowledgeDocRelation.class);
        verify(relationRepository).save(cap.capture());
        assertEquals(docId, cap.getValue().getDocumentId());
        assertEquals(tdnId, cap.getValue().getRelatedDocumentId());
        assertEquals(2, r.results().get(0).warnings().size());
    }

    @Test
    @DisplayName("apagar manda para a lixeira; inexistente ou já apagado devolve false")
    void deleteSoft() {
        KnowledgeDocument d = saved(UUID.randomUUID());
        when(repository.findByDocTypeAndExternalId("skill", "s1")).thenReturn(Optional.of(d));
        when(repository.findByDocTypeAndExternalId("skill", "nada")).thenReturn(Optional.empty());

        assertTrue(service.delete("skill", "s1", "u1"));
        assertEquals("deleted", d.getStatus());
        assertEquals("u1", d.getDeletedBy());
        assertFalse(service.delete("skill", "s1", "u1"));
        assertFalse(service.delete("skill", "nada", "u1"));
        assertThrows(ResponseStatusException.class, () -> service.delete("xx", "s1", "u1"));
        verify(repository, times(1)).save(any());
    }

    @Test
    @DisplayName("manifesto rejeita docType desconhecido")
    void manifestValidatesType() {
        assertThrows(ResponseStatusException.class, () -> service.manifest("nada"));
        when(repository.findManifest(null)).thenReturn(List.of());
        assertTrue(service.manifest("  ").isEmpty());
        verify(repository, never()).findManifest(eq("nada"));
        verify(repository, atLeastOnce()).findManifest(any());
        verifyNoMoreInteractions(ignoreStubs(relationRepository));
    }
}
