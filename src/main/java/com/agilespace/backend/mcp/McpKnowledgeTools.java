package com.agilespace.backend.mcp;

import com.agilespace.backend.domain.KnowledgeDocument;
import com.agilespace.backend.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Ferramentas MCP da Base de Conhecimento. Reaproveita o KnowledgeService existente —
 * list/get usam a mesma lógica do KnowledgeApiV1Controller (/api/v1/knowledge/docs);
 * import usa o mesmo saveOrUpdateDocument (upsert por tdnId) do KnowledgeController.
 * Sem escopo de squad/workspace — documentos da KB não têm esse conceito hoje.
 */
@Component
@RequiredArgsConstructor
public class McpKnowledgeTools {

    private final KnowledgeService knowledgeService;

    @Tool(description = "Lista documentos da base de conhecimento do Espaço Ágil, com busca textual opcional")
    public DocumentPage listDocuments(
            @ToolParam(description = "Termo de busca livre (título/conteúdo/categoria); vazio lista os mais recentes", required = false) String query,
            @ToolParam(description = "Página, 0-based (padrão 0)", required = false) Integer page,
            @ToolParam(description = "Tamanho da página (padrão 20)", required = false) Integer size) {
        int p = page != null ? page : 0;
        int s = size != null ? size : 20;
        // Page<T> não é suportado como retorno de @Tool pelo Spring AI (é tratado como
        // "functional type" e o método inteiro é silenciosamente ignorado no registro) —
        // por isso achatamos pro record abaixo em vez de retornar knowledgeService... direto.
        Page<KnowledgeDocument> result = knowledgeService.listDocuments(query, null, null, PageRequest.of(p, s));
        return new DocumentPage(result.getContent(), result.getTotalElements(), result.getNumber(), result.getSize());
    }

    public record DocumentPage(java.util.List<KnowledgeDocument> content, long totalElements, int page, int size) {
    }

    @Tool(description = "Busca um documento da base de conhecimento pelo id")
    public KnowledgeDocument getDocument(@ToolParam(description = "UUID do documento") String id) {
        return knowledgeService.getDocumentById(UUID.fromString(id));
    }

    @Tool(description = "Importa (cria) um novo documento na base de conhecimento do Espaço Ágil")
    public KnowledgeDocument importDocument(
            @ToolParam(description = "Título do documento") String title,
            @ToolParam(description = "Conteúdo do documento (texto ou HTML)") String content,
            @ToolParam(description = "Categoria opcional", required = false) String category,
            @ToolParam(description = "Tags separadas por vírgula, opcional", required = false) String tags) {
        KnowledgeDocument doc = KnowledgeDocument.builder()
                .title(title)
                .content(content)
                .category(category)
                .authorId(McpRequestContext.callerId())
                .status("published")
                .tags(parseTags(tags))
                .byteSize((long) content.getBytes(StandardCharsets.UTF_8).length)
                .build();
        return knowledgeService.saveOrUpdateDocument(doc);
    }

    private static Set<String> parseTags(String tags) {
        Set<String> result = new HashSet<>();
        if (tags == null || tags.isBlank()) {
            return result;
        }
        for (String tag : tags.split(",")) {
            String trimmed = tag.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
