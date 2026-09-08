package com.agilespace.backend.security;

import com.agilespace.backend.domain.ApiKey;
import com.agilespace.backend.repository.ApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

/**
 * Exige uma API key válida (header X-Api-Key) em /api/v1/** e /mcp/** — a API
 * pública de leitura da Base de Conhecimento e o servidor MCP, pensados pra
 * chamada de máquina/serviço, não sessão de usuário logado. Nenhum dos dois
 * passa pelo JwtAuthenticationFilter (/api/v1 está em PUBLIC_PATHS lá; /mcp
 * nem começa com /api/ então já é ignorado por ele) justamente pra cair aqui
 * em vez de exigir Bearer JWT.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
@RequiredArgsConstructor
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String ATTR_API_KEY_ID = "authApiKeyId";

    /**
     * Atributos de escopo (fase 3 do plano de API key com escopo — ver
     * com.agilespace.backend.security.ApiKeyAccess pro enforcement REST e
     * com.agilespace.backend.mcp.ApiKeyTransportContextExtractor pro MCP,
     * os dois lendo daqui em vez de repetir a consulta ao ApiKeyRepository).
     */
    public static final String ATTR_API_KEY_OWNER_ID = "authApiKeyOwnerId";
    public static final String ATTR_API_KEY_SQUAD_ID = "authApiKeySquadId";
    public static final String ATTR_API_KEY_SCOPES = "authApiKeyScopes";
    /** true = chave criada antes do campo ownerRole existir — ver ApiKey.hasFullAccessGrandfathered(). */
    public static final String ATTR_API_KEY_GRANDFATHERED = "authApiKeyGrandfathered";

    private final ApiKeyRepository apiKeyRepository;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        return !(uri.startsWith("/api/v1/") || uri.startsWith("/mcp"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String rawKey = request.getHeader("X-Api-Key");
        if (rawKey == null || rawKey.isBlank()) {
            reject(response, "Chave de API ausente.");
            return;
        }

        String keyHash = ApiKeyHashing.sha256Hex(rawKey);
        Optional<ApiKey> apiKey = apiKeyRepository.findByKeyHashAndRevokedAtIsNull(keyHash);
        if (apiKey.isEmpty()) {
            reject(response, "Chave de API inválida ou revogada.");
            return;
        }

        ApiKey key = apiKey.get();
        key.setLastUsedAt(LocalDateTime.now());
        apiKeyRepository.save(key);

        request.setAttribute(ATTR_API_KEY_ID, key.getId().toString());
        request.setAttribute(ATTR_API_KEY_OWNER_ID, key.getOwnerUserId());
        request.setAttribute(ATTR_API_KEY_SQUAD_ID, key.getSquadId());
        request.setAttribute(ATTR_API_KEY_SCOPES, key.getScopes() != null ? key.getScopes() : Set.<String>of());
        request.setAttribute(ATTR_API_KEY_GRANDFATHERED, key.hasFullAccessGrandfathered());
        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\": \"" + message + "\"}");
    }
}
