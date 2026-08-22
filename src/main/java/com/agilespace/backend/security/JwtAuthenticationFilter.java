package com.agilespace.backend.security;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;

/**
 * Exige um JWT válido em todas as rotas /api/** (exceto login/register/docs).
 * Endpoints sob /api/admin/** exigem adicionalmente role=ADMIN.
 * Requisições autenticadas expõem userId/userEmail/userRole como atributos do request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String ATTR_USER_ID = "authUserId";
    public static final String ATTR_USER_EMAIL = "authUserEmail";
    public static final String ATTR_USER_ROLE = "authUserRole";

    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/login",
            "/api/auth/register"
    );

    private final JwtTokenUtil jwtTokenUtil;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/")) {
            return true;
        }
        return PUBLIC_PATHS.stream().anyMatch(uri::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Token de autenticação ausente");
            return;
        }

        JsonNode claims = jwtTokenUtil.validateAndExtractClaims(authHeader);
        if (claims == null || !claims.hasNonNull("sub")) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Token de autenticação inválido ou expirado");
            return;
        }

        String role = claims.hasNonNull("role") ? claims.get("role").asText() : "MEMBER";

        if (request.getRequestURI().startsWith("/api/admin") && !"ADMIN".equals(role)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "Acesso restrito a administradores");
            return;
        }

        request.setAttribute(ATTR_USER_ID, claims.get("sub").asText());
        request.setAttribute(ATTR_USER_EMAIL, claims.hasNonNull("email") ? claims.get("email").asText() : null);
        request.setAttribute(ATTR_USER_ROLE, role);

        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\": \"" + message + "\"}");
    }
}
