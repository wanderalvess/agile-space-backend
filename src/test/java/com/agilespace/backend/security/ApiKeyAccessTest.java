package com.agilespace.backend.security;

import com.agilespace.backend.domain.ApiKeyScope;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyAccess - Enforcement de Escopos em Endpoints REST")
class ApiKeyAccessTest {

    @Mock
    private HttpServletRequest request;

    @Nested
    @DisplayName("Verificação de Escopos")
    class RequireScopeTests {

        @Test
        @DisplayName("Deve permitir se chave tem escopo requerido")
        void shouldAllowWithValidScope() {
            Set<String> scopes = Set.of("KNOWLEDGE_READ", "SQUAD_READ");
            when(request.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SCOPES))
                    .thenReturn(scopes);
            lenient().when(request.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_GRANDFATHERED))
                    .thenReturn(false);

            assertDoesNotThrow(() ->
                ApiKeyAccess.requireScope(request, ApiKeyScope.KNOWLEDGE_READ)
            );
        }

        @Test
        @DisplayName("Deve rejeitar se chave não tem escopo requerido")
        void shouldRejectWithoutRequiredScope() {
            Set<String> scopes = Set.of("SQUAD_READ");
            when(request.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SCOPES))
                    .thenReturn(scopes);
            when(request.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_GRANDFATHERED))
                    .thenReturn(false);

            ResponseStatusException exception = assertThrows(
                    ResponseStatusException.class,
                    () -> ApiKeyAccess.requireScope(request, ApiKeyScope.KNOWLEDGE_READ)
            );

            assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
            assertTrue(exception.getReason().contains("KNOWLEDGE_READ"));
        }

        @Test
        @DisplayName("Deve rejeitar se scopes está vazio")
        void shouldRejectWithEmptyScopes() {
            Set<String> scopes = Set.of();
            when(request.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SCOPES))
                    .thenReturn(scopes);
            when(request.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_GRANDFATHERED))
                    .thenReturn(false);

            ResponseStatusException exception = assertThrows(
                    ResponseStatusException.class,
                    () -> ApiKeyAccess.requireScope(request, ApiKeyScope.POKER_READ)
            );

            assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        }

        @Test
        @DisplayName("Deve permitir com escopo se chave é grandfathered")
        void shouldAllowGrandfatheredKeyAnyScope() {
            lenient().when(request.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SCOPES))
                    .thenReturn(Set.of());
            when(request.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_GRANDFATHERED))
                    .thenReturn(true);

            assertDoesNotThrow(() ->
                ApiKeyAccess.requireScope(request, ApiKeyScope.KNOWLEDGE_WRITE)
            );
        }

        @Test
        @DisplayName("Deve ignorar case na comparação de escopos")
        void shouldIgnoreCaseInScopeComparison() {
            Set<String> scopes = Set.of("knowledge_read"); // lowercase
            when(request.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SCOPES))
                    .thenReturn(scopes);
            when(request.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_GRANDFATHERED))
                    .thenReturn(false);

            assertDoesNotThrow(() ->
                ApiKeyAccess.requireScope(request, ApiKeyScope.KNOWLEDGE_READ) // uppercase enum
            );
        }
    }

    @Nested
    @DisplayName("Resolução de Owner User ID")
    class OwnerUserIdTests {

        @Test
        @DisplayName("Deve retornar ownerUserId quando definido")
        void shouldReturnOwnerUserIdWhenSet() {
            when(request.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_OWNER_ID))
                    .thenReturn("user-123");

            String result = ApiKeyAccess.ownerUserIdOrFallback(request, "fallback-user");

            assertEquals("user-123", result);
        }

        @Test
        @DisplayName("Deve retornar fallback quando ownerUserId é null")
        void shouldReturnFallbackWhenOwnerIdNull() {
            when(request.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_OWNER_ID))
                    .thenReturn(null);

            String result = ApiKeyAccess.ownerUserIdOrFallback(request, "fallback-user");

            assertEquals("fallback-user", result);
        }

        @Test
        @DisplayName("Deve preferir ownerUserId real ao fallback")
        void shouldPreferRealOwnerIdOverFallback() {
            when(request.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_OWNER_ID))
                    .thenReturn("real-user");

            String result = ApiKeyAccess.ownerUserIdOrFallback(request, "fallback-user");

            assertEquals("real-user", result);
            assertNotEquals("fallback-user", result);
        }
    }

    @Nested
    @DisplayName("Casos Extremos")
    class EdgeCasesTests {

        @Test
        @DisplayName("Deve rejeitar se scopes é null (não é grandfathered)")
        void shouldRejectWhenScopesNullAndNotGrandfathered() {
            when(request.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SCOPES))
                    .thenReturn(null);
            when(request.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_GRANDFATHERED))
                    .thenReturn(false);

            ResponseStatusException exception = assertThrows(
                    ResponseStatusException.class,
                    () -> ApiKeyAccess.requireScope(request, ApiKeyScope.KNOWLEDGE_READ)
            );

            assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        }

        @Test
        @DisplayName("Deve permitir se scopes é null mas é grandfathered")
        void shouldAllowWhenScopesNullButGrandfathered() {
            lenient().when(request.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SCOPES))
                    .thenReturn(null);
            when(request.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_GRANDFATHERED))
                    .thenReturn(true);

            assertDoesNotThrow(() ->
                ApiKeyAccess.requireScope(request, ApiKeyScope.KNOWLEDGE_READ)
            );
        }

        @Test
        @DisplayName("Deve permitir múltiplos escopos em uma chave")
        void shouldAllowMultipleScopesInKey() {
            Set<String> scopes = Set.of(
                    "KNOWLEDGE_READ",
                    "KNOWLEDGE_WRITE",
                    "SQUAD_READ",
                    "POKER_READ"
            );
            when(request.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SCOPES))
                    .thenReturn(scopes);
            when(request.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_GRANDFATHERED))
                    .thenReturn(false);

            // Todos devem passar
            assertDoesNotThrow(() -> ApiKeyAccess.requireScope(request, ApiKeyScope.KNOWLEDGE_READ));
            assertDoesNotThrow(() -> ApiKeyAccess.requireScope(request, ApiKeyScope.KNOWLEDGE_WRITE));
            assertDoesNotThrow(() -> ApiKeyAccess.requireScope(request, ApiKeyScope.SQUAD_READ));
            assertDoesNotThrow(() -> ApiKeyAccess.requireScope(request, ApiKeyScope.POKER_READ));
        }
    }
}
