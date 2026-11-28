package com.agilespace.backend.service;

import com.agilespace.backend.service.KnowledgeChunker.Chunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("KnowledgeChunker - trechos por seção")
class KnowledgeChunkerTest {

    @Test
    @DisplayName("vazio ou só espaços não gera trechos")
    void emptyContent() {
        assertTrue(KnowledgeChunker.chunk(null).isEmpty());
        assertTrue(KnowledgeChunker.chunk("  \n \n").isEmpty());
    }

    @Test
    @DisplayName("cabeçalhos viram caminho e seções diferentes não se misturam")
    void headingPathsAndSections() {
        String md = "# Pedidos\nIntro curta.\n\n## Criar\nPOST /pedidos cria um pedido.\n\n### Validações\nCliente obrigatório.\n\n## Cancelar\nDELETE cancela.";
        List<Chunk> chunks = KnowledgeChunker.chunk(md);

        assertEquals(4, chunks.size());
        assertEquals("Pedidos", chunks.get(0).headingPath());
        assertEquals("Pedidos > Criar", chunks.get(1).headingPath());
        assertEquals("Pedidos > Criar > Validações", chunks.get(2).headingPath());
        assertEquals("Pedidos > Cancelar", chunks.get(3).headingPath());
        assertTrue(chunks.get(3).text().contains("DELETE"));
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).ordinal());
        }
    }

    @Test
    @DisplayName("texto sem cabeçalho (.txt do TDN) é uma seção só, agrupada por tamanho")
    void plainTextGroupedBySize() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append("Parágrafo ").append(i).append(" ").append("palavra ".repeat(40)).append("\n\n");
        }
        List<Chunk> chunks = KnowledgeChunker.chunk(sb.toString());

        assertTrue(chunks.size() > 1);
        chunks.forEach(c -> {
            assertEquals("", c.headingPath());
            assertTrue(c.text().length() <= KnowledgeChunker.MAX_CHARS, "trecho passou do máximo: " + c.text().length());
        });
        String joined = String.join("\n\n", chunks.stream().map(Chunk::text).toList());
        assertTrue(joined.contains("Parágrafo 0 ") && joined.contains("Parágrafo 11 "));
    }

    @Test
    @DisplayName("# dentro de bloco de código não é cabeçalho e o bloco fica inteiro")
    void codeFenceKeptIntact() {
        String md = "## Exemplo\nVeja:\n\n```java\n# comentario\nclass A {}\n\nclass B {}\n```\n\nFim.";
        List<Chunk> chunks = KnowledgeChunker.chunk(md);

        assertEquals(1, chunks.size());
        assertEquals("Exemplo", chunks.get(0).headingPath());
        assertTrue(chunks.get(0).text().contains("# comentario"));
        assertTrue(chunks.get(0).text().contains("class A {}\n\nclass B {}") || chunks.get(0).text().contains("class A {}\nclass B {}"));
    }

    @Test
    @DisplayName("bloco enorme sem quebras é cortado e nada passa do máximo")
    void hugeBlockSplit() {
        String giant = "linha de codigo ".repeat(400);
        List<Chunk> chunks = KnowledgeChunker.chunk("## Grande\n" + giant);

        assertTrue(chunks.size() >= 5);
        chunks.forEach(c -> assertTrue(c.text().length() <= KnowledgeChunker.MAX_CHARS));
    }

    @Test
    @DisplayName("seção só com título não gera trecho, mas aparece no caminho dos filhos")
    void headingOnlySectionSkipped() {
        List<Chunk> chunks = KnowledgeChunker.chunk("# A\n## B\ntexto");

        assertEquals(1, chunks.size());
        assertEquals("A > B", chunks.get(0).headingPath());
    }

    @Test
    @DisplayName("determinístico: mesmo texto, mesmos trechos")
    void deterministic() {
        String md = "# T\n" + "frase. ".repeat(300) + "\n\n## S\nfim";
        assertEquals(KnowledgeChunker.chunk(md), KnowledgeChunker.chunk(md));
    }
}
