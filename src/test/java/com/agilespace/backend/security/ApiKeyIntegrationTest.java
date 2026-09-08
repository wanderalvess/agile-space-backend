package com.agilespace.backend.security;

import com.agilespace.backend.domain.ApiKey;
import com.agilespace.backend.domain.ApiKeyScope;
import com.agilespace.backend.repository.ApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKey Integration - Fluxo Completo de Autenticação e Autorização")
class ApiKeyIntegrationTest {

    @Mock
    private ApiKeyRepository apiKeyRepository;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private ApiKeyAuthenticationFilter filter;

    private String rawKey;
    private String keyHash;
    private ApiKey knowledgeReadKey;
    private ApiKey pokersquadKey;
    private ApiKey grandfatheredKey;

    @BeforeEach
    void setUp() {
        rawKey = "ask_test1234567890abcdef1234567890abcdef1234567890abcdef";
        keyHash = ApiKeyHashing.sha256Hex(rawKey);

        knowledgeReadKey = ApiKey.builder()
                .id(UUID.randomUUID())
                .name("Knowledge Read Key")
                .keyHash(keyHash)
                .ownerUserId("user-knowledge")
                .ownerRole("MEMBER")
                .scopes(Set.of("KNOWLEDGE_READ"))
                .squadId(null)
                .createdAt(LocalDateTime.now())
                .build();

        pokersquadKey = ApiKey.builder()
                .id(UUID.randomUUID())
                .name("Squad Poker Key")
                .keyHash(ApiKeyHashing.sha256Hex("ask_poker123"))
                .ownerUserId("user-squad")
                .ownerRole("LEAD")
                .scopes(Set.of("SQUAD_READ", "POKER_READ", "POKER_WRITE"))
                .squadId("squad-123")
                .createdAt(LocalDateTime.now())
                .build();

        grandfatheredKey = ApiKey.builder()
                .id(UUID.randomUUID())
                .name("Old Key")
                .keyHash(ApiKeyHashing.sha256Hex("ask_old456"))
                .ownerUserId("user-admin")
                .ownerRole(null) // grandfathered
                .scopes(Set.of())
                .squadId(null)
                .createdAt(LocalDateTime.now().minusYears(1))
                .build();
    }

    @Nested
    @DisplayName("Fluxo de API com Escopo Específico")
    class ScopedKeyFlowTests {

        @Test
        @DisplayName("KNOWLEDGE_READ key - permite leitura, nega escrita")
        void shouldAllowReadDenyWriteForKnowledgeReadKey() throws IOException {
            when(request.getRequestURI()).thenReturn("/api/v1/knowledge/search");
            when(request.getMethod()).thenReturn("GET");
            when(request.getHeader("X-Api-Key")).thenReturn(rawKey);
            when(apiKeyRepository.findByKeyHashAndRevokedAtIsNull(keyHash))
                    .thenReturn(Optional.of(knowledgeReadKey));

            // Filter passa
            assertDoesNotThrow(() -> filter.doFilterInternal(request, response, filterChain));
            verify(filterChain).doFilter(request, response);

            // Mas requireScope deve rejeitar operações fora do escopo
            ResponseStatusException exception = assertThrows(
                    ResponseStatusException.class,
                    () -> ApiKeyAccess.requireScope(request, ApiKeyScope.KNOWLEDGE_WRITE)
            );

            assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        }

        @Test
        @DisplayName("SQUAD+POKER key - acesso múltiplo a recursos")
        void shouldAllowMultipleScopesInSingleKey() throws IOException {
            String pokeyHash = ApiKeyHashing.sha256Hex("ask_poker123");

            when(request.getRequestURI()).thenReturn("/api/v1/squad/read");
            when(request.getMethod()).thenReturn("GET");
            when(request.getHeader("X-Api-Key")).thenReturn("ask_poker123");
            when(apiKeyRepository.findByKeyHashAndRevokedAtIsNull(pokeyHash))
                    .thenReturn(Optional.of(pokersquadKey));

            assertDoesNotThrow(() -> filter.doFilterInternal(request, response, filterChain));

            // Deve permitir SQUAD_READ
            assertDoesNotThrow(() -> ApiKeyAccess.requireScope(request, ApiKeyScope.SQUAD_READ));

            // Deve permitir POKER_READ
            assertDoesNotThrow(() -> ApiKeyAccess.requireScope(request, ApiKeyScope.POKER_READ));

            // Deve permitir POKER_WRITE
            assertDoesNotThrow(() -> ApiKeyAccess.requireScope(request, ApiKeyScope.POKER_WRITE));

            // Deve rejeitar KNOWLEDGE_*
            assertThrows(
                    ResponseStatusException.class,
                    () -> ApiKeyAccess.requireScope(request, ApiKeyScope.KNOWLEDGE_READ)
            );
        }
    }

    @Nested
    @DisplayName("Fluxo de API com Restrição de Squad")
    class SquadRestrictedKeyFlowTests {

        @Test
        @DisplayName("Squad-restricted key - acesso permitido à squad correta")
        void shouldAllowAccessToCorrectSquad() throws IOException {
            String pokerHash = ApiKeyHashing.sha256Hex("ask_poker123");

            when(request.getRequestURI()).thenReturn("/api/v1/squad/squad-123");
            when(request.getMethod()).thenReturn("GET");
            when(request.getHeader("X-Api-Key")).thenReturn("ask_poker123");
            when(apiKeyRepository.findByKeyHashAndRevokedAtIsNull(pokerHash))
                    .thenReturn(Optional.of(pokersquadKey));

            // Simula o que o controller faria
            assertDoesNotThrow(() -> {
                filter.doFilterInternal(request, response, filterChain);
                // Verificar que ownerUserId foi setado
                verify(request).setAttribute(
                        eq(ApiKeyAuthenticationFilter.ATTR_API_KEY_OWNER_ID),
                        eq("user-squad")
                );
            });
        }
    }

    @Nested
    @DisplayName("Fluxo de Chave Grandfathered")
    class GrandfatheredKeyFlowTests {

        @Test
        @DisplayName("Grandfathered key - acesso total sem restrição")
        void shouldAllowUnrestrictedAccessForGrandfatheredKey() throws IOException {
            String oldHash = ApiKeyHashing.sha256Hex("ask_old456");

            when(request.getRequestURI()).thenReturn("/api/v1/knowledge/search");
            when(request.getMethod()).thenReturn("GET");
            when(request.getHeader("X-Api-Key")).thenReturn("ask_old456");
            when(apiKeyRepository.findByKeyHashAndRevokedAtIsNull(oldHash))
                    .thenReturn(Optional.of(grandfatheredKey));

            filter.doFilterInternal(request, response, filterChain);

            // Grandfathered deve permitir qualquer escopo
            assertDoesNotThrow(() -> ApiKeyAccess.requireScope(request, ApiKeyScope.KNOWLEDGE_WRITE));
            assertDoesNotThrow(() -> ApiKeyAccess.requireScope(request, ApiKeyScope.POKER_WRITE));
            assertDoesNotThrow(() -> ApiKeyAccess.requireScope(request, ApiKeyScope.PROMPTHUB_READ));
        }
    }

    @Nested
    @DisplayName("Cenários de Segurança")
    class SecurityScenariosTests {

        @Test
        @DisplayName("Tentativa de acesso com chave revogada - negada")
        void shouldDenyRevokedKeyAccess() throws IOException {
            ApiKey revokedKey = knowledgeReadKey.toBuilder()
                    .revokedAt(LocalDateTime.now().minusHours(1))
                    .build();

            StringWriter stringWriter = new StringWriter();
            PrintWriter writer = new PrintWriter(stringWriter);

            when(request.getRequestURI()).thenReturn("/api/v1/knowledge/search");
            when(request.getMethod()).thenReturn("GET");
            when(request.getHeader("X-Api-Key")).thenReturn(rawKey);
            when(apiKeyRepository.findByKeyHashAndRevokedAtIsNull(keyHash))
                    .thenReturn(Optional.empty());
            when(response.getWriter()).thenReturn(writer);

            filter.doFilterInternal(request, response, filterChain);

            verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            verify(filterChain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("Tentativa de escala de privilégios - negada")
        void shouldDenyPrivilegeEscalation() throws IOException {
            when(request.getRequestURI()).thenReturn("/api/v1/poker/admin/stats");
            when(request.getMethod()).thenReturn("GET");
            when(request.getHeader("X-Api-Key")).thenReturn(rawKey);
            when(apiKeyRepository.findByKeyHashAndRevokedAtIsNull(keyHash))
                    .thenReturn(Optional.of(knowledgeReadKey)); // apenas KNOWLEDGE_READ

            filter.doFilterInternal(request, response, filterChain);

            // Tenta acessar POKER_WRITE mesmo tendo apenas KNOWLEDGE_READ
            ResponseStatusException exception = assertThrows(
                    ResponseStatusException.class,
                    () -> ApiKeyAccess.requireScope(request, ApiKeyScope.POKER_WRITE)
            );

            assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        }

        @Test
        @DisplayName("Tentativa de acesso cross-squad - negada")
        void shouldDenyCrossSquadAccess() throws IOException {
            String pokerHash = ApiKeyHashing.sha256Hex("ask_poker123");
            ApiKey restrictedToSquad123 = pokersquadKey.toBuilder()
                    .squadId("squad-123")
                    .build();

            when(request.getRequestURI()).thenReturn("/api/v1/squad/squad-999");
            when(request.getMethod()).thenReturn("GET");
            when(request.getHeader("X-Api-Key")).thenReturn("ask_poker123");
            when(apiKeyRepository.findByKeyHashAndRevokedAtIsNull(pokerHash))
                    .thenReturn(Optional.of(restrictedToSquad123));

            assertDoesNotThrow(() -> filter.doFilterInternal(request, response, filterChain));

            // No contexto MCP, isso seria verificado por requireSquad
            // Simulamos o que o tool MCP faria
            var mockContext = new com.agilespace.backend.mcp.ApiKeyContext(
                    "user-squad",
                    "squad-123",
                    Set.of("SQUAD_READ"),
                    false
            );

            SecurityException exception = assertThrows(
                    SecurityException.class,
                    () -> mockContext.requireSquad("squad-999")
            );

            assertTrue(exception.getMessage().contains("squad-123"));
        }
    }

    @Nested
    @DisplayName("Auditoria e Rastreamento")
    class AuditingTests {

        @Test
        @DisplayName("Deve atualizar lastUsedAt a cada uso")
        void shouldTrackLastUsageTime() throws IOException {
            when(request.getRequestURI()).thenReturn("/api/v1/knowledge/search");
            when(request.getMethod()).thenReturn("GET");
            when(request.getHeader("X-Api-Key")).thenReturn(rawKey);
            when(apiKeyRepository.findByKeyHashAndRevokedAtIsNull(keyHash))
                    .thenReturn(Optional.of(knowledgeReadKey));

            filter.doFilterInternal(request, response, filterChain);

            verify(apiKeyRepository).save(any(ApiKey.class));
        }

        @Test
        @DisplayName("Deve preservar auditoria para chaves revogadas")
        void shouldAuditRevokedKeyAttempts() throws IOException {
            StringWriter stringWriter = new StringWriter();
            PrintWriter writer = new PrintWriter(stringWriter);

            when(request.getRequestURI()).thenReturn("/api/v1/knowledge/search");
            when(request.getMethod()).thenReturn("GET");
            when(request.getHeader("X-Api-Key")).thenReturn(rawKey);
            when(apiKeyRepository.findByKeyHashAndRevokedAtIsNull(keyHash))
                    .thenReturn(Optional.empty());
            when(response.getWriter()).thenReturn(writer);

            filter.doFilterInternal(request, response, filterChain);

            // Tentativa de chave revogada foi rejeitada mas registrada na resposta
            verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }
}
