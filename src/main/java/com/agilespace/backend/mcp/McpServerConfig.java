package com.agilespace.backend.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerSseProperties;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Registra os componentes @Tool (McpKnowledgeTools, McpSquadTools, McpPokerTools,
 * McpPromptHubTools) como ToolCallbackProvider — a auto-configuração do
 * spring-ai-starter-mcp-server-webmvc descobre esse bean e expõe cada método
 * anotado como uma ferramenta MCP.
 *
 * @EnableConfigurationProperties(McpServerSseProperties.class) é necessário aqui
 * porque McpServerSseWebMvcAutoConfiguration (quem normalmente registra essa
 * properties bean via essa mesma anotação) tem @ConditionalOnMissingBean(
 * McpServerTransportProvider.class) NO NÍVEL DA CLASSE — o bean
 * webMvcSseServerTransportProvider abaixo, sendo um WebMvcSseServerTransportProvider
 * (subtipo de McpServerTransportProvider), satisfaz essa condição e a auto-config
 * inteira é pulada, properties incluída. Confirmado por decompilação em 2026-09-08:
 * mvn test com @SpringBootTest falhava com NoSuchBeanDefinitionException pra
 * McpServerSseProperties até esta linha entrar.
 */
@Configuration
@EnableConfigurationProperties(McpServerSseProperties.class)
@RequiredArgsConstructor
public class McpServerConfig {

    private final ApiKeyTransportContextExtractor apiKeyTransportContextExtractor;

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

    /**
     * Substitui o bean equivalente de McpServerSseWebMvcAutoConfiguration
     * (@Bean + @ConditionalOnMissingBean nessa classe — confirmado por
     * decompilação em 2026-09-08, não por documentação) só pra acrescentar
     * .contextExtractor(...). O resto é réplica exata da construção original
     * (mesma leitura de McpServerSseProperties, também decompilada), pra não
     * mudar nenhum outro comportamento do transporte SSE além da atribuição
     * de API key por chamada — pré-requisito da fase 3 do plano de escopo por
     * chave (ver C:\Users\wande\.claude\plans\api-key-scoping-selfservice.md).
     */
    @Bean
    public WebMvcSseServerTransportProvider webMvcSseServerTransportProvider(
            @Qualifier("mcpServerObjectMapper") ObjectMapper objectMapper, McpServerSseProperties properties) {
        return WebMvcSseServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .baseUrl(properties.getBaseUrl())
                .sseEndpoint(properties.getSseEndpoint())
                .messageEndpoint(properties.getSseMessageEndpoint())
                .keepAliveInterval(properties.getKeepAliveInterval())
                .contextExtractor(apiKeyTransportContextExtractor)
                .build();
    }

    /**
     * Réplica do segundo bean de McpServerSseWebMvcAutoConfiguration — também
     * pulado pela mesma @ConditionalOnMissingBean de classe inteira. Sem este
     * bean, o provider existe mas nada registra as rotas /mcp/sse e
     * /mcp/message no Spring MVC (404 em ambas — confirmado ao vivo com um
     * client MCP real em 2026-09-08 antes deste bean entrar).
     */
    @Bean
    public RouterFunction<ServerResponse> webMvcSseServerRouterFunction(WebMvcSseServerTransportProvider transportProvider) {
        return transportProvider.getRouterFunction();
    }
}
