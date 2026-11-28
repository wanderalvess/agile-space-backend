package com.agilespace.backend.service;

import com.agilespace.backend.domain.KnowledgeChunk;
import com.agilespace.backend.domain.KnowledgeDocument;
import com.agilespace.backend.dto.KnowledgeSearchDtos.EmbeddingFill;
import com.agilespace.backend.dto.KnowledgeSearchDtos.FillResponse;
import com.agilespace.backend.dto.KnowledgeSearchDtos.SearchRequest;
import com.agilespace.backend.repository.KnowledgeChunkRepository;
import com.agilespace.backend.repository.KnowledgeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("KnowledgeChunkService - trechos, embeddings pendentes e fusão da busca")
class KnowledgeChunkServiceTest {

    @Mock
    private KnowledgeChunkRepository chunkRepository;
    @Mock
    private KnowledgeRepository knowledgeRepository;
    @Mock
    private NamedParameterJdbcTemplate jdbc;
    @InjectMocks
    private KnowledgeChunkService service;

    private static List<Double> vec(int n, double v) {
        return IntStream.range(0, n).mapToObj(i -> v).collect(Collectors.toList());
    }

    @Test
    @DisplayName("rebuild reaproveita o embedding de trecho com mesmo caminho+texto")
    void rebuildReusesEmbeddingOfUnchangedChunk() {
        UUID docId = UUID.randomUUID();
        KnowledgeDocument doc = KnowledgeDocument.builder().id(docId).title("Doc")
                .content("## A\ntexto igual\n\n## B\ntexto novo").build();
        KnowledgeChunk old = KnowledgeChunk.builder().documentId(docId).ordinal(0).headingPath("A").text("texto igual")
                .textHash(KnowledgeChunkService.textHash("A", "texto igual")).embedding(new float[]{0.5f}).build();
        when(chunkRepository.findByDocumentIdOrderByOrdinalAsc(docId)).thenReturn(List.of(old));

        int n = service.rebuild(doc);

        assertEquals(2, n);
        ArgumentCaptor<List<KnowledgeChunk>> cap = ArgumentCaptor.forClass(List.class);
        verify(chunkRepository).deleteAllByDocumentId(docId);
        verify(chunkRepository).saveAll(cap.capture());
        List<KnowledgeChunk> saved = cap.getValue();
        assertArrayEquals(new float[]{0.5f}, saved.get(0).getEmbedding());
        assertNull(saved.get(1).getEmbedding());
        assertEquals("B", saved.get(1).getHeadingPath());
    }

    @Test
    @DisplayName("fill recusa vetor de tamanho errado, id inválido e chunk inexistente")
    void fillValidates() {
        UUID ok = UUID.randomUUID();
        UUID ghost = UUID.randomUUID();
        KnowledgeChunk chunk = KnowledgeChunk.builder().id(ok).documentId(UUID.randomUUID()).ordinal(0).text("t").build();
        when(chunkRepository.findByIdIn(any())).thenReturn(List.of(chunk));

        FillResponse r = service.fill(List.of(
                new EmbeddingFill(ok.toString(), vec(KnowledgeChunkService.EMBEDDING_DIMS, 0.05)),
                new EmbeddingFill(UUID.randomUUID().toString(), vec(10, 0.1)),
                new EmbeddingFill("não-é-uuid", vec(KnowledgeChunkService.EMBEDDING_DIMS, 0.1)),
                new EmbeddingFill(ghost.toString(), vec(KnowledgeChunkService.EMBEDDING_DIMS, 0.1))));

        assertEquals(1, r.updated());
        assertEquals(3, r.rejected().size());
        assertNotNull(chunk.getEmbedding());
        assertEquals(KnowledgeChunkService.EMBEDDING_DIMS, chunk.getEmbedding().length);
        assertTrue(r.rejected().contains(ghost.toString()));
    }

    @Test
    @DisplayName("fill recusa lote vazio ou acima do limite")
    void fillBatchLimits() {
        assertThrows(ResponseStatusException.class, () -> service.fill(List.of()));
        List<EmbeddingFill> big = new ArrayList<>();
        for (int i = 0; i <= KnowledgeChunkService.MAX_PENDING_BATCH; i++) {
            big.add(new EmbeddingFill(UUID.randomUUID().toString(), vec(KnowledgeChunkService.EMBEDDING_DIMS, 0.1)));
        }
        assertThrows(ResponseStatusException.class, () -> service.fill(big));
    }

    @Test
    @DisplayName("busca exige query e/ou embedding e valida o tamanho do vetor")
    void searchValidation() {
        assertThrows(ResponseStatusException.class, () -> service.search(null));
        assertThrows(ResponseStatusException.class,
                () -> service.search(new SearchRequest("  ", null, null, null, null, null)));
        assertThrows(ResponseStatusException.class,
                () -> service.search(new SearchRequest("pedido", vec(5, 0.1), null, null, null, null)));
    }

    @Test
    @DisplayName("RRF: trecho presente nas duas listas vence o que aparece só em uma")
    void fuseFavorsAgreement() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();

        var fused = KnowledgeChunkService.fuse(List.of(a, b), List.of(c, b), 10);

        assertEquals(b, fused.get(0).id());
        assertEquals(2, fused.get(0).textRank());
        assertEquals(2, fused.get(0).vectorRank());
        assertEquals(3, fused.size());
        assertNull(fused.stream().filter(f -> f.id().equals(a)).findFirst().orElseThrow().vectorRank());
        assertEquals(2, KnowledgeChunkService.fuse(List.of(a, b), List.of(c), 2).size());
    }

    @Test
    @DisplayName("tsquery: palavras úteis unidas por OR, sem pontuação; vazio vira nulo")
    void tsQueryBuilding() {
        assertEquals("como or criar or pedido", KnowledgeChunkService.toTsQuery("Como criar o pedido?"));
        assertNull(KnowledgeChunkService.toTsQuery("  ? "));
        assertNull(KnowledgeChunkService.toTsQuery(null));
        assertEquals("order or service", KnowledgeChunkService.toTsQuery("order_service; DROP"
                .replace("; DROP", "")));
    }

    @Test
    @DisplayName("entrada do embedding: título, caminho e trecho")
    void embeddingInputLayout() {
        assertEquals("Doc\nA > B\ncorpo", KnowledgeChunkService.embeddingInput("Doc", "A > B", "corpo"));
        assertEquals("corpo", KnowledgeChunkService.embeddingInput(null, "", "corpo"));
    }

    @Test
    @DisplayName("backfill processa até 100 docs sem trechos e avisa se ainda restam")
    void backfillBatches() {
        List<KnowledgeDocument> docs = new ArrayList<>();
        for (int i = 0; i < KnowledgeChunkService.BACKFILL_BATCH + 1; i++) {
            docs.add(KnowledgeDocument.builder().id(UUID.randomUUID()).title("D" + i).content("## S\ntexto " + i).build());
        }
        when(knowledgeRepository.findWithoutChunks(any())).thenReturn(docs);

        var r = service.backfill();

        assertEquals(KnowledgeChunkService.BACKFILL_BATCH, r.documents());
        assertEquals(KnowledgeChunkService.BACKFILL_BATCH, r.chunks());
        assertTrue(r.more());
        verify(chunkRepository, times(KnowledgeChunkService.BACKFILL_BATCH)).saveAll(any());
    }

    @Test
    @DisplayName("backfill sem pendências devolve zero e more=false")
    void backfillNothingToDo() {
        when(knowledgeRepository.findWithoutChunks(any())).thenReturn(List.of());

        var r = service.backfill();

        assertEquals(0, r.documents());
        assertFalse(r.more());
    }
}
