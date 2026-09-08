package com.agilespace.backend.security;

import com.agilespace.backend.domain.ApiKeyScope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

/**
 * Checagem de escopo pros controllers REST autenticados por API key
 * (KnowledgeApiV1Controller, PromptHubApiV1Controller). Lê os atributos que
 * ApiKeyAuthenticationFilter já colocou na request — não repete a consulta ao
 * ApiKeyRepository.
 */
public final class ApiKeyAccess {

    private ApiKeyAccess() {
    }

    /** 403 se a chave que autenticou a request não tiver o escopo pedido. */
    public static void requireScope(HttpServletRequest request, ApiKeyScope scope) {
        Boolean grandfathered = (Boolean) request.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_GRANDFATHERED);
        if (Boolean.TRUE.equals(grandfathered)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Set<String> scopes = (Set<String>) request.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_SCOPES);
        boolean has = scopes != null && scopes.stream().anyMatch(s -> s.equalsIgnoreCase(scope.name()));
        if (!has) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chave de API sem escopo " + scope.name() + ".");
        }
    }

    /** dono da chave que autenticou a request, ou o fallback se a chave for anônima/antiga. */
    public static String ownerUserIdOrFallback(HttpServletRequest request, String fallback) {
        String ownerUserId = (String) request.getAttribute(ApiKeyAuthenticationFilter.ATTR_API_KEY_OWNER_ID);
        return ownerUserId != null ? ownerUserId : fallback;
    }
}
