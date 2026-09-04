package com.agilespace.backend.mcp;

/**
 * Identidade de "caller" usada onde o service subjacente exige um id de usuário
 * (ex.: authorId de KnowledgeDocument, creatorId de PokerRoom).
 *
 * Nota: tentamos originalmente resolver o ownerUserId real da API key (via
 * ApiKeyAuthenticationFilter + RequestContextHolder), igual um controller comum
 * faz. Não funciona aqui — o transporte SSE do spring-ai-starter-mcp-server-webmvc
 * (WebMvcSseServerTransportProvider) executa o método @Tool fora da thread da
 * requisição HTTP que abriu a conexão/enviou a mensagem, então o ThreadLocal do
 * RequestContextHolder já não está mais presente ("No thread-bound request found",
 * confirmado em teste manual). McpSyncServerExchange (o único objeto de contexto
 * que o SDK do MCP entrega dentro de um @Tool) também não expõe o HttpServletRequest
 * nem headers — só capabilities/roots/sampling do cliente MCP. Sem isso, não dá
 * pra atribuir a chamada a uma API key específica. Não é regressão: hoje nenhum
 * endpoint /api/v1 tem atribuição por chave além de lastUsedAt global, então uma
 * identidade fixa pra escrita via MCP é consistente com o modelo de confiança
 * existente (chave válida = acesso total).
 */
final class McpRequestContext {

    private static final String CALLER_ID = "mcp-server";

    private McpRequestContext() {
    }

    static String callerId() {
        return CALLER_ID;
    }
}
