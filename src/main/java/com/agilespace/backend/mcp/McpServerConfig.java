package com.agilespace.backend.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registra os componentes @Tool (McpKnowledgeTools, McpSquadTools, McpPokerTools,
 * McpPromptHubTools) como ToolCallbackProvider — a auto-configuração do
 * spring-ai-starter-mcp-server-webmvc descobre esse bean e expõe cada método
 * anotado como uma ferramenta MCP.
 */
@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider agileSpaceMcpTools(
            McpKnowledgeTools knowledgeTools,
            McpSquadTools squadTools,
            McpPokerTools pokerTools,
            McpPromptHubTools promptHubTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(knowledgeTools, squadTools, pokerTools, promptHubTools)
                .build();
    }
}
