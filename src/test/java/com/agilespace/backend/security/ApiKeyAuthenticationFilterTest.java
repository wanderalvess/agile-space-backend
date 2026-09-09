package com.agilespace.backend.security;

import com.agilespace.backend.domain.ApiKey;
import com.agilespace.backend.repository.ApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyAuthenticationFilter - Autenticação por Chave de API")
class ApiKeyAuthenticationFilterTest {

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

    private ApiKey validApiKey;
    private String rawKey;
    private String keyHash;

    @BeforeEach
    void setUp() {
        rawKey = "ask_1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";
        keyHash = ApiKeyHashing.sha256Hex(rawKey);

        validApiKey = ApiKey.builder()
                .id(UUID.randomUUID())
                .name("Test API Key")
                .keyHash(keyHash)
                .ownerUserId("user-123")
                .ownerRole("ADMIN")
                .scopes(Set.of("KNOWLEDGE_READ", "SQUAD_READ"))
                .squadId(null)
                .createdAt(LocalDateTime.now())
                .lastUsedAt(null)
                .revokedAt(null)
                .build();
    }

    @Nested
    @DisplayName("Validação de Chave de API")
    class KeyValidationTests {

        @Test
        @DisplayName("Deve permitir requisição com chave válida e não-revogada")
        void shouldAllowValidKey() throws ServletException, IOException {
            lenient().when(request.getRequestURI()).thenReturn("/api/v1/knowledge/search");
            lenient().when(request.getMethod()).thenReturn("GET");
            when(request.getHeader("X-Api-Key")).thenReturn(rawKey);
            when(apiKeyRepository.findByKeyHashAndRevokedAtIsNull(keyHash))
                    .thenReturn(Optional.of(validApiKey));

            filter.doFilterInternal(request, response, filterChain);

            verify(filterChain, times(1)).doFilter(request, response);
            verify(response, never()).setStatus(anyInt());
        }

        @Test
        @DisplayName("Deve rejeitar requisição sem header X-Api-Key")
        void shouldRejectMissingKey() throws ServletException, IOException {
            StringWriter stringWriter = new StringWriter();
            PrintWriter writer = new PrintWriter(stringWriter);

            lenient().when(request.getRequestURI()).thenReturn("/api/v1/knowledge/search");
            lenient().when(request.getMethod()).thenReturn("GET");
            when(request.getHeader("X-Api-Key")).thenReturn(null);
            when(response.getWriter()).thenReturn(writer);

            filter.doFilterInternal(request, response, filterChain);

            verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            verify(response).setContentType(MediaType.APPLICATION_JSON_VALUE);
            verify(filterChain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("Deve rejeitar chave de API inválida")
        void shouldRejectInvalidKey() throws ServletException, IOException {
            StringWriter stringWriter = new StringWriter();
            PrintWriter writer = new PrintWriter(stringWriter);

            lenient().when(request.getRequestURI()).thenReturn("/api/v1/knowledge/search");
            lenient().when(request.getMethod()).thenReturn("GET");
            when(request.getHeader("X-Api-Key")).thenReturn(rawKey);
            when(apiKeyRepository.findByKeyHashAndRevokedAtIsNull(keyHash))
                    .thenReturn(Optional.empty());
            when(response.getWriter()).thenReturn(writer);

            filter.doFilterInternal(request, response, filterChain);

            verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            verify(filterChain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("Deve rejeitar chave revogada")
        void shouldRejectRevokedKey() throws ServletException, IOException {
            StringWriter stringWriter = new StringWriter();
            PrintWriter writer = new PrintWriter(stringWriter);

            ApiKey revokedKey = ApiKey.builder()
                    .id(UUID.randomUUID())
                    .name("Revoked Key")
                    .keyHash(keyHash)
                    .ownerUserId("user-123")
                    .revokedAt(LocalDateTime.now().minusDays(1))
                    .build();

            lenient().when(request.getRequestURI()).thenReturn("/api/v1/knowledge/search");
            lenient().when(request.getMethod()).thenReturn("GET");
            when(request.getHeader("X-Api-Key")).thenReturn(rawKey);
            when(apiKeyRepository.findByKeyHashAndRevokedAtIsNull(keyHash))
                    .thenReturn(Optional.empty());
            when(response.getWriter()).thenReturn(writer);

            filter.doFilterInternal(request, response, filterChain);

            verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            verify(filterChain, never()).doFilter(any(), any());
        }
    }

    @Nested
    @DisplayName("Atributos de Requisição com API Key")
    class RequestAttributesTests {

        @Test
        @DisplayName("Deve setar atributos de escopo na requisição")
        void shouldSetScopeAttributes() throws ServletException, IOException {
            lenient().when(request.getRequestURI()).thenReturn("/api/v1/knowledge/search");
            lenient().when(request.getMethod()).thenReturn("GET");
            when(request.getHeader("X-Api-Key")).thenReturn(rawKey);
            when(apiKeyRepository.findByKeyHashAndRevokedAtIsNull(keyHash))
                    .thenReturn(Optional.of(validApiKey));

            filter.doFilterInternal(request, response, filterChain);

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
            verify(request, atLeastOnce()).setAttribute(keyCaptor.capture(), valueCaptor.capture());

            assertTrue(keyCaptor.getAllValues().contains(ApiKeyAuthenticationFilter.ATTR_API_KEY_OWNER_ID));
            assertTrue(keyCaptor.getAllValues().contains(ApiKeyAuthenticationFilter.ATTR_API_KEY_SCOPES));
            assertTrue(keyCaptor.getAllValues().contains(ApiKeyAuthenticationFilter.ATTR_API_KEY_GRANDFATHERED));
        }

        @Test
        @DisplayName("Deve setar ownerUserId correto")
        void shouldSetCorrectOwnerId() throws ServletException, IOException {
            lenient().when(request.getRequestURI()).thenReturn("/api/v1/knowledge/search");
            lenient().when(request.getMethod()).thenReturn("GET");
            when(request.getHeader("X-Api-Key")).thenReturn(rawKey);
            when(apiKeyRepository.findByKeyHashAndRevokedAtIsNull(keyHash))
                    .thenReturn(Optional.of(validApiKey));

            filter.doFilterInternal(request, response, filterChain);

            verify(request).setAttribute(
                    eq(ApiKeyAuthenticationFilter.ATTR_API_KEY_OWNER_ID),
                    eq("user-123")
            );
        }

        @Test
        @DisplayName("Deve setar scopes na requisição")
        void shouldSetScopes() throws ServletException, IOException {
            lenient().when(request.getRequestURI()).thenReturn("/api/v1/knowledge/search");
            lenient().when(request.getMethod()).thenReturn("GET");
            when(request.getHeader("X-Api-Key")).thenReturn(rawKey);
            when(apiKeyRepository.findByKeyHashAndRevokedAtIsNull(keyHash))
                    .thenReturn(Optional.of(validApiKey));

            filter.doFilterInternal(request, response, filterChain);

            ArgumentCaptor<Object> scopeCaptor = ArgumentCaptor.forClass(Object.class);
            verify(request).setAttribute(
                    eq(ApiKeyAuthenticationFilter.ATTR_API_KEY_SCOPES),
                    scopeCaptor.capture()
            );

            @SuppressWarnings("unchecked")
            Set<String> scopes = (Set<String>) scopeCaptor.getValue();
            assertTrue(scopes.contains("KNOWLEDGE_READ"));
            assertTrue(scopes.contains("SQUAD_READ"));
        }

        @Test
        @DisplayName("Deve setar grandfathered=false pra chave com ownerRole")
        void shouldSetGrandfatheredFalseWithOwnerRole() throws ServletException, IOException {
            lenient().when(request.getRequestURI()).thenReturn("/api/v1/knowledge/search");
            lenient().when(request.getMethod()).thenReturn("GET");
            when(request.getHeader("X-Api-Key")).thenReturn(rawKey);
            when(apiKeyRepository.findByKeyHashAndRevokedAtIsNull(keyHash))
                    .thenReturn(Optional.of(validApiKey));

            filter.doFilterInternal(request, response, filterChain);

            verify(request).setAttribute(
                    eq(ApiKeyAuthenticationFilter.ATTR_API_KEY_GRANDFATHERED),
                    eq(false)
            );
        }

        @Test
        @DisplayName("Deve setar grandfathered=true pra chave sem ownerRole")
        void shouldSetGrandfatheredTrueWithoutOwnerRole() throws ServletException, IOException {
            ApiKey oldKey = ApiKey.builder()
                    .id(UUID.randomUUID())
                    .name("Old API Key")
                    .keyHash(keyHash)
                    .ownerUserId("user-123")
                    .ownerRole(null) // sem role = grandfathered
                    .scopes(Set.of())
                    .createdAt(LocalDateTime.now())
                    .build();

            lenient().when(request.getRequestURI()).thenReturn("/api/v1/knowledge/search");
            lenient().when(request.getMethod()).thenReturn("GET");
            when(request.getHeader("X-Api-Key")).thenReturn(rawKey);
            when(apiKeyRepository.findByKeyHashAndRevokedAtIsNull(keyHash))
                    .thenReturn(Optional.of(oldKey));

            filter.doFilterInternal(request, response, filterChain);

            verify(request).setAttribute(
                    eq(ApiKeyAuthenticationFilter.ATTR_API_KEY_GRANDFATHERED),
                    eq(true)
            );
        }
    }

    @Nested
    @DisplayName("Filtragem de Requisições")
    class FilteringScopeTests {

        @Test
        @DisplayName("Deve ignorar requisições OPTIONS")
        void shouldIgnoreOptionsRequests() throws ServletException, IOException {
            when(request.getMethod()).thenReturn("OPTIONS");

            assertTrue(filter.shouldNotFilter(request));
        }

        @Test
        @DisplayName("Deve filtrar requisições /api/v1/**")
        void shouldFilterApiV1Requests() throws ServletException, IOException {
            when(request.getRequestURI()).thenReturn("/api/v1/knowledge/search");

            assertFalse(filter.shouldNotFilter(request));
        }

        @Test
        @DisplayName("Deve filtrar requisições /mcp/**")
        void shouldFilterMcpRequests() throws ServletException, IOException {
            when(request.getRequestURI()).thenReturn("/mcp/sse");

            assertFalse(filter.shouldNotFilter(request));
        }

        @Test
        @DisplayName("Deve ignorar outras requisições")
        void shouldIgnoreOtherRequests() throws ServletException, IOException {
            when(request.getRequestURI()).thenReturn("/api/auth/login");

            assertTrue(filter.shouldNotFilter(request));
        }
    }

    @Nested
    @DisplayName("Atualização de lastUsedAt")
    class LastUsedAtTest {

        @Test
        @DisplayName("Deve atualizar lastUsedAt ao usar chave")
        void shouldUpdateLastUsedAt() throws ServletException, IOException {
            lenient().when(request.getRequestURI()).thenReturn("/api/v1/knowledge/search");
            lenient().when(request.getMethod()).thenReturn("GET");
            when(request.getHeader("X-Api-Key")).thenReturn(rawKey);
            when(apiKeyRepository.findByKeyHashAndRevokedAtIsNull(keyHash))
                    .thenReturn(Optional.of(validApiKey));

            filter.doFilterInternal(request, response, filterChain);

            ArgumentCaptor<ApiKey> keyCaptor = ArgumentCaptor.forClass(ApiKey.class);
            verify(apiKeyRepository).save(keyCaptor.capture());
            assertNotNull(keyCaptor.getValue().getLastUsedAt());
        }
    }
}
