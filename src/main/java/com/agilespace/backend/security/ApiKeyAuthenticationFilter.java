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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Exige uma API key válida (header X-Api-Key) em /api/v1/** — a API pública
 * de leitura da Base de Conhecimento, pensada pra chamada de máquina/serviço
 * (ex: servidor MCP), não sessão de usuário logado. /api/v1 está isento do
 * JwtAuthenticationFilter (ver PUBLIC_PATHS lá) justamente pra cair aqui
 * em vez de exigir Bearer JWT.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
@RequiredArgsConstructor
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String ATTR_API_KEY_ID = "authApiKeyId";

    private final ApiKeyRepository apiKeyRepository;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String rawKey = request.getHeader("X-Api-Key");
        if (rawKey == null || rawKey.isBlank()) {
            reject(response, "Chave de API ausente.");
            return;
        }

        String keyHash = sha256Hex(rawKey);
        Optional<ApiKey> apiKey = apiKeyRepository.findByKeyHashAndRevokedAtIsNull(keyHash);
        if (apiKey.isEmpty()) {
            reject(response, "Chave de API inválida ou revogada.");
            return;
        }

        ApiKey key = apiKey.get();
        key.setLastUsedAt(LocalDateTime.now());
        apiKeyRepository.save(key);

        request.setAttribute(ATTR_API_KEY_ID, key.getId().toString());
        filterChain.doFilter(request, response);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\": \"" + message + "\"}");
    }
}
