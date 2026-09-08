package com.agilespace.backend.mcp;

import com.agilespace.backend.domain.ApiKeyScope;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyContext - Contexto de Autenticação MCP")
class ApiKeyContextTest {

    private ApiKeyContext context;

    @BeforeEach
    void setUp() {
        context = new ApiKeyContext(
                "user-123",
                "squad-456",
                Set.of("KNOWLEDGE_READ", "SQUAD_READ"),
                false
        );
    }

    @Nested
    @DisplayName("Criação do Contexto")
    class ContextCreationTests {

        @Test
        @DisplayName("Deve criar contexto com todos os parâmetros")
        void shouldCreateContextWithAllParameters() {
            assertNotNull(context);
            assertEquals("user-123", context.ownerUserId());
            assertEquals("squad-456", context.squadId());
            assertEquals(2, context.scopes().size());
            assertFalse(context.grandfathered());
        }

        @Test
        @DisplayName("Deve criar contexto grandfathered")
        void shouldCreateGrandfatheredContext() {
            ApiKeyContext oldContext = new ApiKeyContext(
                    "user-old",
                    null,
                    Set.of(),
                    true
            );

            assertTrue(oldContext.grandfathered());
            assertTrue(oldContext.scopes().isEmpty());
        }

        @Test
        @DisplayName("Deve criar contexto sem restrição de squad")
        void shouldCreateContextWithoutSquadRestriction() {
            ApiKeyContext unrestrictedContext = new ApiKeyContext(
                    "user-123",
                    null, // sem restrição de squad
                    Set.of("KNOWLEDGE_READ"),
                    false
            );

            assertNull(unrestrictedContext.squadId());
        }
    }

    @Nested
    @DisplayName("Verificação de Escopos")
    class RequireScopeTests {

        @Test
        @DisplayName("Deve permitir se contexto tem escopo requerido")
        void shouldAllowWithValidScope() {
            assertDoesNotThrow(() -> context.requireScope(ApiKeyScope.KNOWLEDGE_READ));
            assertDoesNotThrow(() -> context.requireScope(ApiKeyScope.SQUAD_READ));
        }

        @Test
        @DisplayName("Deve rejeitar se contexto não tem escopo requerido")
        void shouldRejectWithoutRequiredScope() {
            SecurityException exception = assertThrows(
                    SecurityException.class,
                    () -> context.requireScope(ApiKeyScope.KNOWLEDGE_WRITE)
            );

            assertTrue(exception.getMessage().contains("KNOWLEDGE_WRITE"));
        }

        @Test
        @DisplayName("Deve permitir qualquer escopo se grandfathered")
        void shouldAllowAnyScopeIfGrandfathered() {
            ApiKeyContext grandfatheredContext = new ApiKeyContext(
                    "user-123",
                    null,
                    Set.of(), // vazio
                    true // grandfathered
            );

            assertDoesNotThrow(() -> grandfatheredContext.requireScope(ApiKeyScope.KNOWLEDGE_WRITE));
            assertDoesNotThrow(() -> grandfatheredContext.requireScope(ApiKeyScope.POKER_WRITE));
        }

        @Test
        @DisplayName("Deve ignorar case na comparação de escopos")
        void shouldIgnoreCaseInScopeComparison() {
            ApiKeyContext contextWithLowercase = new ApiKeyContext(
                    "user-123",
                    null,
                    Set.of("knowledge_read"), // lowercase
                    false
            );

            assertDoesNotThrow(() ->
                contextWithLowercase.requireScope(ApiKeyScope.KNOWLEDGE_READ) // uppercase enum
            );
        }
    }

    @Nested
    @DisplayName("Verificação de Squad")
    class RequireSquadTests {

        @Test
        @DisplayName("Deve permitir se requeste squad bate com contexto")
        void shouldAllowMatchingSquad() {
            assertDoesNotThrow(() -> context.requireSquad("squad-456"));
        }

        @Test
        @DisplayName("Deve rejeitar se requeste squad não bate")
        void shouldRejectMismatchingSquad() {
            SecurityException exception = assertThrows(
                    SecurityException.class,
                    () -> context.requireSquad("squad-999")
            );

            assertTrue(exception.getMessage().contains("squad-456"));
        }

        @Test
        @DisplayName("Deve permitir qualquer squad se contexto não tem restrição")
        void shouldAllowAnySquadIfUnrestricted() {
            ApiKeyContext unrestrictedContext = new ApiKeyContext(
                    "user-123",
                    null, // sem restrição
                    Set.of("SQUAD_READ"),
                    false
            );

            assertDoesNotThrow(() -> unrestrictedContext.requireSquad("squad-999"));
            assertDoesNotThrow(() -> unrestrictedContext.requireSquad("squad-123"));
        }

        @Test
        @DisplayName("Deve permitir qualquer squad se grandfathered")
        void shouldAllowAnySquadIfGrandfathered() {
            ApiKeyContext grandfatheredContext = new ApiKeyContext(
                    "user-123",
                    "squad-456",
                    Set.of("SQUAD_READ"),
                    true
            );

            assertDoesNotThrow(() -> grandfatheredContext.requireSquad("squad-999"));
        }

        @Test
        @DisplayName("Deve ignorar case na comparação de squad")
        void shouldIgnoreCaseInSquadComparison() {
            ApiKeyContext contextWithLowercase = new ApiKeyContext(
                    "user-123",
                    "squad-456",
                    Set.of("SQUAD_READ"),
                    false
            );

            assertDoesNotThrow(() -> contextWithLowercase.requireSquad("SQUAD-456"));
        }
    }

    @Nested
    @DisplayName("Resolução de Owner User ID")
    class OwnerUserIdTests {

        @Test
        @DisplayName("Deve retornar ownerUserId quando definido")
        void shouldReturnOwnerUserIdWhenSet() {
            String result = context.ownerUserIdOrFallback("fallback");

            assertEquals("user-123", result);
            assertNotEquals("fallback", result);
        }

        @Test
        @DisplayName("Deve retornar fallback quando ownerUserId é null")
        void shouldReturnFallbackWhenOwnerIdNull() {
            ApiKeyContext contextWithoutOwner = new ApiKeyContext(
                    null,
                    "squad-456",
                    Set.of("SQUAD_READ"),
                    false
            );

            String result = contextWithoutOwner.ownerUserIdOrFallback("fallback-user");

            assertEquals("fallback-user", result);
        }
    }

    @Nested
    @DisplayName("Segurança Múltipla")
    class CombinedSecurityTests {

        @Test
        @DisplayName("Deve enforçar tanto escopo quanto squad")
        void shouldEnforceScopeAndSquad() {
            // Escopo permite, mas squad não bate
            SecurityException exception = assertThrows(
                    SecurityException.class,
                    () -> {
                        context.requireScope(ApiKeyScope.KNOWLEDGE_READ);
                        context.requireSquad("squad-999");
                    }
            );

            assertTrue(exception.getMessage().contains("squad"));
        }

        @Test
        @DisplayName("Deve permitir operações válidas após todas as verificações")
        void shouldAllowOperationsAfterAllChecks() {
            assertDoesNotThrow(() -> {
                context.requireScope(ApiKeyScope.KNOWLEDGE_READ);
                context.requireSquad("squad-456");
                assertEquals("user-123", context.ownerUserIdOrFallback("fallback"));
            });
        }
    }

    @Nested
    @DisplayName("Casos Extremos")
    class EdgeCasesTests {

        @Test
        @DisplayName("Deve lidar com contexto vazio (sem scopes, sem squad)")
        void shouldHandleEmptyContext() {
            ApiKeyContext emptyContext = new ApiKeyContext(
                    "user-123",
                    null,
                    Set.of(),
                    false
            );

            assertThrows(
                    SecurityException.class,
                    () -> emptyContext.requireScope(ApiKeyScope.KNOWLEDGE_READ)
            );

            // Squad sem restrição é OK
            assertDoesNotThrow(() -> emptyContext.requireSquad("any-squad"));
        }

        @Test
        @DisplayName("Deve permitir contexto grandfathered vazio")
        void shouldAllowEmptyGrandfatheredContext() {
            ApiKeyContext oldContext = new ApiKeyContext(
                    "user-old",
                    null,
                    Set.of(),
                    true
            );

            // Tudo deve passar por ser grandfathered
            assertDoesNotThrow(() -> oldContext.requireScope(ApiKeyScope.POKER_WRITE));
            assertDoesNotThrow(() -> oldContext.requireSquad("squad-999"));
        }
    }
}
