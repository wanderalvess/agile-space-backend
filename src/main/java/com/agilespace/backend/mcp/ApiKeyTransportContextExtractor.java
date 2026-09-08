package com.agilespace.backend.mcp;

import com.agilespace.backend.security.ApiKeyAuthenticationFilter;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;

import java.util.Map;
import java.util.Set;

/**
 * Extrai dono/squad/escopos da API key pro McpTransportContext, a partir dos
 * atributos que ApiKeyAuthenticationFilter já colocou na HttpServletRequest —
 * o filtro (Ordered.HIGHEST_PRECEDENCE + 11) roda antes de qualquer
 * RouterFunction (ordem de filtro do servlet, não de configuração), então
 * esses atributos já existem quando este extractor roda, tanto no GET que
 * abre a conexão SSE quanto no POST de cada mensagem. Registrado no builder
 * do WebMvcSseServerTransportProvider em McpServerConfig.
 *
 * Dentro de um @Tool, o acesso é por ApiKeyContext.from(toolContext) — o SDK
 * do MCP não injeta McpSyncServerExchange direto como parâmetro do método
 * (MethodToolCallback só conhece org.springframework.ai.chat.model.ToolContext
 * como tipo "especial", verificado por decompilação em 2026-09-08, não por
 * documentação); é McpToolUtils (spring-ai-mcp) quem põe o McpSyncServerExchange
 * dentro do ToolContext ao converter cada ToolCallback pra tool MCP.
 */
@Component
public class ApiKeyTransportContextExtractor implements McpTransportContextExtractor<ServerRequest> {

    static final String CONTEXT_KEY = "apiKeyContext";

    @Override
    public McpTransportContext extract(ServerRequest request) {
        HttpServletRequest httpRequest = request.servletRequest();
        String ownerUserId = (String) httpRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_OWNER_ID);
        String squadId = (String) httpRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SQUAD_ID);
        @SuppressWarnings("unchecked")
        Set<String> scopes = (Set<String>) httpRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SCOPES);
        Boolean grandfathered = (Boolean) httpRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_GRANDFATHERED);

        ApiKeyContext context = new ApiKeyContext(
                ownerUserId,
                squadId,
                scopes != null ? scopes : Set.of(),
                Boolean.TRUE.equals(grandfathered)
        );
        return McpTransportContext.create(Map.of(CONTEXT_KEY, context));
    }
}
