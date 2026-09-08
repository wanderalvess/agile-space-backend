package com.agilespace.backend.mcp;

import com.agilespace.backend.domain.ApiKeyScope;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;

import java.util.Set;

/**
 * Dono/squad/escopos da API key que abriu a conexão MCP atual — extraído do
 * McpTransportContext (populado por ApiKeyTransportContextExtractor) via o
 * McpSyncServerExchange que McpToolUtils.getMcpExchange entrega dentro do
 * ToolContext. Todo @Tool deste pacote declara um parâmetro ToolContext
 * toolContext (nunca aparece no schema exposto ao cliente MCP — é o único
 * tipo que MethodToolCallback trata como injeção de framework) e chama
 * ApiKeyContext.from(toolContext) antes de fazer qualquer coisa.
 */
public record ApiKeyContext(String ownerUserId, String squadId, Set<String> scopes, boolean grandfathered) {

    public static ApiKeyContext from(ToolContext toolContext) {
        McpSyncServerExchange exchange = McpToolUtils.getMcpExchange(toolContext)
                .orElseThrow(() -> new IllegalStateException(
                        "Tool MCP chamada sem McpSyncServerExchange no ToolContext — essas tools só são " +
                        "expostas via servidor MCP, não via tool-calling direto de chat."));
        McpTransportContext transportContext = exchange.transportContext();
        Object raw = transportContext.get(ApiKeyTransportContextExtractor.CONTEXT_KEY);
        if (raw instanceof ApiKeyContext context) {
            return context;
        }
        throw new IllegalStateException(
                "McpTransportContext sem ApiKeyContext — ApiKeyTransportContextExtractor não rodou " +
                "(confirme que o bean webMvcSseServerTransportProvider em McpServerConfig está sendo usado).");
    }

    /** Chave criada antes do escopo existir (ver ApiKey.hasFullAccessGrandfathered) — sem restrição. */
    public void requireScope(ApiKeyScope scope) {
        if (grandfathered) {
            return;
        }
        boolean has = scopes != null && scopes.stream().anyMatch(s -> s.equalsIgnoreCase(scope.name()));
        if (!has) {
            throw new SecurityException("Chave de API sem escopo " + scope.name() + ".");
        }
    }

    /**
     * squadId null = chave sem restrição de squad (toda chave criada por
     * ADMIN/LEAD hoje). Mais restritivo que a sessão JWT comum, de propósito
     * — ver ressalva no plano sobre McpSquadTools.
     */
    public void requireSquad(String requestedSquadId) {
        if (grandfathered || squadId == null) {
            return;
        }
        if (!squadId.equalsIgnoreCase(requestedSquadId)) {
            throw new SecurityException("Chave de API restrita à squad " + squadId + ".");
        }
    }

    /** Dono real da chave, ou o fallback se a chave for anônima/antiga (ownerUserId nunca deveria ser null hoje). */
    public String ownerUserIdOrFallback(String fallback) {
        return ownerUserId != null ? ownerUserId : fallback;
    }
}
