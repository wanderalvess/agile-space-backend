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
 * Ferramentas MCP do Prompt Hub (iniciativas, prompts, gems). Usa
 * PromptService.listPublicPrompts (não o listPrompts genérico) — esse restringe a
 * visibility="public" mesmo quando authorId é informado, então uma API key MCP só
 * enxerga o que qualquer usuário anônimo/externo já veria.
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
        Page<Prompt> result = promptService.listPublicPrompts(query, authorId, PageRequest.of(p, s));
        return new PromptPage(result.getContent(), result.getTotalElements(), result.getNumber(), result.getSize());
    }

    public record PromptPage(java.util.List<Prompt> content, long totalElements, int page, int size) {
    }

    @Tool(description = "Busca um prompt/iniciativa do Prompt Hub pelo id")
    public Prompt getPrompt(@ToolParam(description = "UUID do prompt") String id) {
        // Não distingue inexistente de privado — mesma mensagem de erro nos dois casos,
        // pra não confirmar pra quem tem só a API key que um id privado existe.
        Prompt prompt = promptService.getPromptById(UUID.fromString(id));
        if (!"public".equals(prompt.getVisibility())) {
            throw new IllegalArgumentException("Prompt not found with id: " + id);
        }
        return prompt;
    }

    @Tool(description = "Lista coleções públicas do Prompt Hub (trilhas de prompts), filtro opcional por dono")
    @Transactional(readOnly = true)
    public CollectionPage listPromptCollections(
            @ToolParam(description = "Filtra por id do dono, opcional", required = false) String ownerId,
            @ToolParam(description = "Página, 0-based (padrão 0)", required = false) Integer page,
            @ToolParam(description = "Tamanho da página (padrão 20)", required = false) Integer size) {
        int p = page != null ? page : 0;
        int s = size != null ? size : 20;
        // Visibilidade sempre fixa em "public" — antes aceitava um parâmetro livre
        // ("public"/"private") que deixava qualquer dono de API key listar TODAS as
        // coleções privadas do sistema (promptService.listCollections("private", null, ...)
        // sem filtro de dono nenhum). Removido, não só ignorado, pra não reabrir por engano.
        Page<PromptCollection> result = promptService.listCollections("public", ownerId, PageRequest.of(p, s));
        // Cada coleção pública ainda carrega seus items via @ManyToMany — sem esse filtro
        // um item privado dentro de coleção pública vazava aqui (só getPromptCollection
        // filtrava antes; a listagem serializa os items direto).
        result.getContent().forEach(collection -> collection.setItems(
                collection.getItems().stream()
                        .filter(p2 -> "public".equals(p2.getVisibility()))
                        .collect(java.util.stream.Collectors.toList())));
        return new CollectionPage(result.getContent(), result.getTotalElements(), result.getNumber(), result.getSize());
    }

    public record CollectionPage(java.util.List<PromptCollection> content, long totalElements, int page, int size) {
    }

    @Tool(description = "Busca uma coleção pública do Prompt Hub pelo id, com os itens (prompts públicos) embutidos")
    @Transactional(readOnly = true)
    public PromptCollection getPromptCollection(@ToolParam(description = "UUID da coleção") String id) {
        // items é @ManyToMany lazy; a serialização do resultado do @Tool roda fora da
        // sessão Hibernate original (mesmo achado do McpSquadTools sobre thread-local
        // perdido no transporte SSE), então forçamos o fetch aqui dentro, ainda com a
        // transação aberta, em vez de deixar a lazy list estourar na hora de virar JSON.
        PromptCollection collection = promptService.getCollectionById(UUID.fromString(id));
        if (!"public".equals(collection.getVisibility())) {
            throw new IllegalArgumentException("Collection not found with id: " + id);
        }
        Hibernate.initialize(collection.getItems());
        // Item privado dentro de uma coleção pública é filtrado, não derruba a resposta
        // inteira (a coleção em si já passou pelo filtro de público acima).
        collection.setItems(collection.getItems().stream()
                .filter(p -> "public".equals(p.getVisibility()))
                .collect(java.util.stream.Collectors.toList()));
        return collection;
    }
}
