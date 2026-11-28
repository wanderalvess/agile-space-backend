package com.agilespace.backend.mcp;

import com.agilespace.backend.domain.ApiKeyScope;
import com.agilespace.backend.domain.KnowledgeDocument;
import com.agilespace.backend.dto.KnowledgeAskDtos.AskRequest;
import com.agilespace.backend.dto.KnowledgeAskDtos.AskResponse;
import com.agilespace.backend.dto.KnowledgeSearchDtos.SearchRequest;
import com.agilespace.backend.dto.KnowledgeSearchDtos.SearchResponse;
import com.agilespace.backend.dto.KnowledgeDivergenceDtos.Report;
import com.agilespace.backend.service.KnowledgeAskService;
import com.agilespace.backend.service.KnowledgeChunkService;
import com.agilespace.backend.service.KnowledgeDivergenceService;
import com.agilespace.backend.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
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
    private final KnowledgeChunkService chunkService;
    private final KnowledgeAskService askService;
    private final KnowledgeDivergenceService divergenceService;

    @Tool(description = "Lista documentos da base de conhecimento do Espaço Ágil, com busca textual opcional")
    public DocumentPage listDocuments(
            @ToolParam(description = "Termo de busca livre (título/conteúdo/categoria); vazio lista os mais recentes", required = false) String query,
            @ToolParam(description = "Página, 0-based (padrão 0)", required = false) Integer page,
            @ToolParam(description = "Tamanho da página (padrão 20)", required = false) Integer size,
            ToolContext toolContext) {
        ApiKeyContext.from(toolContext).requireScope(ApiKeyScope.KNOWLEDGE_READ);
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

    @Tool(description = "Busca trechos relevantes na base de conhecimento (docs de código das APIs Java, TDN, prompts, skills). "
            + "Devolve trechos com título, seção e link de origem para citação")
    public SearchResponse searchKnowledge(
            @ToolParam(description = "Pergunta ou termos de busca") String query,
            @ToolParam(description = "Quantidade de trechos (padrão 8, máximo 50)", required = false) Integer k,
            @ToolParam(description = "Filtro por tipo: api_doc, tdn_page, prompt, skill ou instruction", required = false) String docType,
            @ToolParam(description = "Filtro por origem: code ou tdn", required = false) String source,
            @ToolParam(description = "Filtro por módulo/aplicação", required = false) String module,
            ToolContext toolContext) {
        ApiKeyContext.from(toolContext).requireScope(ApiKeyScope.KNOWLEDGE_READ);
        return chunkService.search(new SearchRequest(query, null, k, docType, source, module));
    }

    @Tool(description = "Monta o contexto para responder uma pergunta: recupera os trechos da base e devolve fontes "
            + "numeradas [n] e o prompt pronto. Responda usando só essas fontes, citando [n]; se código e TDN divergirem, "
            + "cite os dois")
    public AskResponse getContext(
            @ToolParam(description = "Pergunta em linguagem natural") String question,
            @ToolParam(description = "Quantidade de trechos (padrão 6)", required = false) Integer k,
            @ToolParam(description = "Filtro por tipo: api_doc, tdn_page, prompt, skill ou instruction", required = false) String docType,
            @ToolParam(description = "Filtro por origem: code ou tdn", required = false) String source,
            @ToolParam(description = "Filtro por módulo/aplicação", required = false) String module,
            ToolContext toolContext) {
        ApiKeyContext.from(toolContext).requireScope(ApiKeyScope.KNOWLEDGE_READ);
        return askService.ask(new AskRequest(question, null, k, docType, source, module, "none"));
    }

    @Tool(description = "Lista divergências entre a documentação de código e as páginas do TDN ligadas a ela: compara "
            + "rotas, classes e campos citados nos dois lados e devolve os pares mais divergentes primeiro, com o que só "
            + "existe em cada lado")
    public Report listDivergences(
            @ToolParam(description = "Filtra por módulo/aplicação", required = false) String module,
            @ToolParam(description = "Máximo de pares (padrão 50)", required = false) Integer limit,
            ToolContext toolContext) {
        ApiKeyContext.from(toolContext).requireScope(ApiKeyScope.KNOWLEDGE_READ);
        return divergenceService.report(module, limit);
    }

    @Tool(description = "Busca um documento da base de conhecimento pelo id")
    public KnowledgeDocument getDocument(
            @ToolParam(description = "UUID do documento") String id,
            ToolContext toolContext) {
        ApiKeyContext.from(toolContext).requireScope(ApiKeyScope.KNOWLEDGE_READ);
        KnowledgeDocument doc = knowledgeService.getDocumentById(UUID.fromString(id));
        // Rascunho, arquivado e lixeira não saem pelo MCP (o assistente externo só vê o que está publicado).
        if (!KnowledgeService.isPubliclyReadable(doc)) {
            throw new IllegalArgumentException("Document not found with id: " + id);
        }
        return doc;
    }

    @Tool(description = "Importa (cria) um novo documento na base de conhecimento do Espaço Ágil")
    public KnowledgeDocument importDocument(
            @ToolParam(description = "Título do documento") String title,
            @ToolParam(description = "Conteúdo do documento (texto ou HTML)") String content,
            @ToolParam(description = "Categoria opcional", required = false) String category,
            @ToolParam(description = "Tags separadas por vírgula, opcional", required = false) String tags,
            ToolContext toolContext) {
        ApiKeyContext ctx = ApiKeyContext.from(toolContext);
        ctx.requireScope(ApiKeyScope.KNOWLEDGE_WRITE);
        KnowledgeDocument doc = KnowledgeDocument.builder()
                .title(title)
                .content(content)
                .category(category)
                .authorId(ctx.ownerUserIdOrFallback("mcp-server"))
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
