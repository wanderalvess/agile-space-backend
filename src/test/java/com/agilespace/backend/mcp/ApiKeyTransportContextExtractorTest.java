package com.agilespace.backend.mcp;

import com.agilespace.backend.security.ApiKeyAuthenticationFilter;
import io.modelcontextprotocol.common.McpTransportContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.function.ServerRequest;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyTransportContextExtractor - Extração de Contexto para MCP")
class ApiKeyTransportContextExtractorTest {

    @Mock
    private ServerRequest serverRequest;

    @Mock
    private HttpServletRequest httpServletRequest;

    @InjectMocks
    private ApiKeyTransportContextExtractor extractor;

    @BeforeEach
    void setUp() {
        when(serverRequest.servletRequest()).thenReturn(httpServletRequest);
    }

    @Nested
    @DisplayName("Extração de Contexto Completo")
    class ExtractionTests {

        @Test
        @DisplayName("Deve extrair contexto completo com todos os atributos")
        void shouldExtractCompleteContext() {
            String ownerUserId = "user-123";
            String squadId = "squad-456";
            Set<String> scopes = Set.of("KNOWLEDGE_READ", "SQUAD_READ");

            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_OWNER_ID))
                    .thenReturn(ownerUserId);
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SQUAD_ID))
                    .thenReturn(squadId);
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SCOPES))
                    .thenReturn(scopes);
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_GRANDFATHERED))
                    .thenReturn(false);

            McpTransportContext context = extractor.extract(serverRequest);

            assertNotNull(context);
            Object raw = context.get(ApiKeyTransportContextExtractor.CONTEXT_KEY);
            assertNotNull(raw);
            assertTrue(raw instanceof ApiKeyContext);

            ApiKeyContext apiKeyContext = (ApiKeyContext) raw;
            assertEquals(ownerUserId, apiKeyContext.ownerUserId());
            assertEquals(squadId, apiKeyContext.squadId());
            assertEquals(scopes, apiKeyContext.scopes());
            assertFalse(apiKeyContext.grandfathered());
        }

        @Test
        @DisplayName("Deve extrair contexto sem restrição de squad")
        void shouldExtractContextWithoutSquadRestriction() {
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_OWNER_ID))
                    .thenReturn("user-123");
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SQUAD_ID))
                    .thenReturn(null);
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SCOPES))
                    .thenReturn(Set.of("KNOWLEDGE_READ"));
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_GRANDFATHERED))
                    .thenReturn(false);

            McpTransportContext context = extractor.extract(serverRequest);

            ApiKeyContext apiKeyContext = (ApiKeyContext) context.get(ApiKeyTransportContextExtractor.CONTEXT_KEY);
            assertNull(apiKeyContext.squadId());
        }

        @Test
        @DisplayName("Deve extrair contexto grandfathered")
        void shouldExtractGrandfatheredContext() {
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_OWNER_ID))
                    .thenReturn("user-old");
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SQUAD_ID))
                    .thenReturn(null);
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SCOPES))
                    .thenReturn(null);
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_GRANDFATHERED))
                    .thenReturn(true);

            McpTransportContext context = extractor.extract(serverRequest);

            ApiKeyContext apiKeyContext = (ApiKeyContext) context.get(ApiKeyTransportContextExtractor.CONTEXT_KEY);
            assertTrue(apiKeyContext.grandfathered());
        }
    }

    @Nested
    @DisplayName("Tratamento de Valores Nulos")
    class NullHandlingTests {

        @Test
        @DisplayName("Deve converter null scopes para set vazio")
        void shouldConvertNullScopesToEmptySet() {
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_OWNER_ID))
                    .thenReturn("user-123");
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SQUAD_ID))
                    .thenReturn("squad-456");
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SCOPES))
                    .thenReturn(null);
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_GRANDFATHERED))
                    .thenReturn(false);

            McpTransportContext context = extractor.extract(serverRequest);

            ApiKeyContext apiKeyContext = (ApiKeyContext) context.get(ApiKeyTransportContextExtractor.CONTEXT_KEY);
            assertNotNull(apiKeyContext.scopes());
            assertTrue(apiKeyContext.scopes().isEmpty());
        }

        @Test
        @DisplayName("Deve manter null ownerUserId")
        void shouldMaintainNullOwnerId() {
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_OWNER_ID))
                    .thenReturn(null);
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SQUAD_ID))
                    .thenReturn("squad-456");
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SCOPES))
                    .thenReturn(Set.of("SQUAD_READ"));
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_GRANDFATHERED))
                    .thenReturn(false);

            McpTransportContext context = extractor.extract(serverRequest);

            ApiKeyContext apiKeyContext = (ApiKeyContext) context.get(ApiKeyTransportContextExtractor.CONTEXT_KEY);
            assertNull(apiKeyContext.ownerUserId());
        }
    }

    @Nested
    @DisplayName("Casos Extremos")
    class EdgeCasesTests {

        @Test
        @DisplayName("Deve extrair contexto minimal (mínimo de atributos)")
        void shouldExtractMinimalContext() {
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_OWNER_ID))
                    .thenReturn(null);
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SQUAD_ID))
                    .thenReturn(null);
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SCOPES))
                    .thenReturn(null);
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_GRANDFATHERED))
                    .thenReturn(false);

            McpTransportContext context = extractor.extract(serverRequest);

            assertNotNull(context);
            ApiKeyContext apiKeyContext = (ApiKeyContext) context.get(ApiKeyTransportContextExtractor.CONTEXT_KEY);
            assertNotNull(apiKeyContext);
            assertFalse(apiKeyContext.grandfathered());
        }

        @Test
        @DisplayName("Deve manter ordem e quantidade de escopos")
        void shouldMaintainScopesOrder() {
            Set<String> scopes = Set.of(
                    "KNOWLEDGE_READ",
                    "KNOWLEDGE_WRITE",
                    "SQUAD_READ",
                    "POKER_READ",
                    "PROMPTHUB_READ"
            );

            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_OWNER_ID))
                    .thenReturn("user-123");
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SQUAD_ID))
                    .thenReturn("squad-456");
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SCOPES))
                    .thenReturn(scopes);
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_GRANDFATHERED))
                    .thenReturn(false);

            McpTransportContext context = extractor.extract(serverRequest);

            ApiKeyContext apiKeyContext = (ApiKeyContext) context.get(ApiKeyTransportContextExtractor.CONTEXT_KEY);
            assertEquals(scopes.size(), apiKeyContext.scopes().size());
            assertTrue(apiKeyContext.scopes().containsAll(scopes));
        }

        @Test
        @DisplayName("Deve booleano grandfathered ser false quando null")
        void shouldTreatNullGrandfatheredAsFalse() {
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_OWNER_ID))
                    .thenReturn("user-123");
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SQUAD_ID))
                    .thenReturn(null);
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SCOPES))
                    .thenReturn(Set.of("KNOWLEDGE_READ"));
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_GRANDFATHERED))
                    .thenReturn(null);

            McpTransportContext context = extractor.extract(serverRequest);

            ApiKeyContext apiKeyContext = (ApiKeyContext) context.get(ApiKeyTransportContextExtractor.CONTEXT_KEY);
            assertFalse(apiKeyContext.grandfathered());
        }
    }

    @Nested
    @DisplayName("Integração com McpTransportContext")
    class TransportContextIntegrationTests {

        @Test
        @DisplayName("Deve armazenar context no transport context com chave correta")
        void shouldStoreContextUnderCorrectKey() {
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_OWNER_ID))
                    .thenReturn("user-123");
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SQUAD_ID))
                    .thenReturn("squad-456");
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SCOPES))
                    .thenReturn(Set.of("KNOWLEDGE_READ"));
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_GRANDFATHERED))
                    .thenReturn(false);

            McpTransportContext context = extractor.extract(serverRequest);

            Object stored = context.get(ApiKeyTransportContextExtractor.CONTEXT_KEY);
            assertNotNull(stored);
            assertTrue(stored instanceof ApiKeyContext);
        }

        @Test
        @DisplayName("Deve retornar McpTransportContext válido")
        void shouldReturnValidMcpTransportContext() {
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_OWNER_ID))
                    .thenReturn("user-123");
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SQUAD_ID))
                    .thenReturn(null);
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SCOPES))
                    .thenReturn(Set.of("KNOWLEDGE_READ"));
            when(httpServletRequest.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_GRANDFATHERED))
                    .thenReturn(false);

            McpTransportContext result = extractor.extract(serverRequest);

            assertNotNull(result);
            assertNotNull(result.get(ApiKeyTransportContextExtractor.CONTEXT_KEY));
        }
    }
}
