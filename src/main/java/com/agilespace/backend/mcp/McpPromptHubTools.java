package com.agilespace.backend.mcp;

import com.agilespace.backend.domain.Prompt;
import com.agilespace.backend.domain.PromptCollection;
import com.agilespace.backend.service.PromptService;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Ferramentas MCP do Prompt Hub (iniciativas, prompts, gems). Reaproveita o
 * PromptService existente — listPrompts sem query/authorId já filtra por
 * visibility="public" (mesmo comportamento do PromptController), então uma
 * API key MCP só enxerga o que qualquer usuário anônimo/externo já veria.
 * Sem tool de escrita por ora (criar/editar prompt fica só via /api/prompts com JWT).
 */
@Component
@RequiredArgsConstructor
public class McpPromptHubTools {

    private final PromptService promptService;

    @Tool(description = "Lista prompts/iniciativas do Prompt Hub (público), com busca textual opcional ou filtro por autor")
    public PromptPage listPrompts(
            @ToolParam(description = "Termo de busca livre (título/descrição/conteúdo); vazio lista os mais recentes públicos", required = false) String query,
            @ToolParam(description = "Filtra por id do autor, opcional", required = false) String authorId,
            @ToolParam(description = "Página, 0-based (padrão 0)", required = false) Integer page,
            @ToolParam(description = "Tamanho da página (padrão 20)", required = false) Integer size) {
        int p = page != null ? page : 0;
        int s = size != null ? size : 20;
        // Page<T> não é suportado como retorno de @Tool pelo Spring AI (vira "functional
        // type" e o método é ignorado no registro sem erro) — achatamos pro record abaixo.
        Page<Prompt> result = promptService.listPrompts(query, authorId, PageRequest.of(p, s));
        return new PromptPage(result.getContent(), result.getTotalElements(), result.getNumber(), result.getSize());
    }

    public record PromptPage(java.util.List<Prompt> content, long totalElements, int page, int size) {
    }

    @Tool(description = "Busca um prompt/iniciativa do Prompt Hub pelo id")
    public Prompt getPrompt(@ToolParam(description = "UUID do prompt") String id) {
        return promptService.getPromptById(UUID.fromString(id));
    }

    @Tool(description = "Lista coleções do Prompt Hub (trilhas de prompts), filtro opcional por visibilidade ou dono")
    public CollectionPage listPromptCollections(
            @ToolParam(description = "Filtra por visibilidade (\"public\"/\"private\"); vazio lista as públicas", required = false) String visibility,
            @ToolParam(description = "Filtra por id do dono, opcional", required = false) String ownerId,
            @ToolParam(description = "Página, 0-based (padrão 0)", required = false) Integer page,
            @ToolParam(description = "Tamanho da página (padrão 20)", required = false) Integer size) {
        int p = page != null ? page : 0;
        int s = size != null ? size : 20;
        Page<PromptCollection> result = promptService.listCollections(visibility, ownerId, PageRequest.of(p, s));
        return new CollectionPage(result.getContent(), result.getTotalElements(), result.getNumber(), result.getSize());
    }

    public record CollectionPage(java.util.List<PromptCollection> content, long totalElements, int page, int size) {
    }

    @Tool(description = "Busca uma coleção do Prompt Hub pelo id, com os itens (prompts) embutidos")
    @Transactional(readOnly = true)
    public PromptCollection getPromptCollection(@ToolParam(description = "UUID da coleção") String id) {
        // items é @ManyToMany lazy; a serialização do resultado do @Tool roda fora da
        // sessão Hibernate original (mesmo achado do McpSquadTools sobre thread-local
        // perdido no transporte SSE), então forçamos o fetch aqui dentro, ainda com a
        // transação aberta, em vez de deixar a lazy list estourar na hora de virar JSON.
        PromptCollection collection = promptService.getCollectionById(UUID.fromString(id));
        Hibernate.initialize(collection.getItems());
        return collection;
    }
}
