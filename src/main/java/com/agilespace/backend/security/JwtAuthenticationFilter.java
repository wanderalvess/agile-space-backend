package com.agilespace.backend.security;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
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
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String ATTR_USER_ID = "authUserId";
    public static final String ATTR_USER_EMAIL = "authUserEmail";
    public static final String ATTR_USER_ROLE = "authUserRole";

    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/forgot-password",
            "/api/public",
            // Execução pública da transformação JOLT no motor Java Bazaarvoice
            "/api/jolt/transform",
            "/api/jolt/engine-info",
            // API pública com API key própria (ApiKeyAuthenticationFilter), não JWT de sessão.
            "/api/v1",
            // Changelog é público por design (visível sem login) — ver ChangelogController.
            "/api/changelog"
    );

    private final JwtTokenUtil jwtTokenUtil;
    private final UserSessionGuard sessionGuard;

    @Autowired
    public JwtAuthenticationFilter(JwtTokenUtil jwtTokenUtil, UserSessionGuard sessionGuard) {
        this.jwtTokenUtil = jwtTokenUtil;
        this.sessionGuard = sessionGuard;
    }

    /** Sem checagem do estado da conta no banco: só assinatura e expiração do token. */
    public JwtAuthenticationFilter(JwtTokenUtil jwtTokenUtil) {
        this(jwtTokenUtil, null);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        // Caminhos com segmentos de navegação (/..) nunca são ignorados: o Tomcat normaliza antes de
        // rotear, mas getRequestURI() devolve o texto cru, e um prefixo "público" ou fora de /api/
        // poderia esconder uma rota protegida. doFilterInternal os recusa.
        if (hasPathTricks(request.getRequestURI())) {
            return false;
        }
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/")) {
            return true;
        }
        return isPublicPath(uri);
    }

    /** Prefixo público só vale como segmento inteiro: "/api/public" e "/api/public/...", nunca "/api/publicX". */
    static boolean isPublicPath(String uri) {
        for (String publicPath : PUBLIC_PATHS) {
            if (uri.equals(publicPath) || uri.startsWith(publicPath + "/")) {
                return true;
            }
        }
        return false;
    }

    static boolean hasPathTricks(String uri) {
        if (uri == null) {
            return false;
        }
        String lower = uri.toLowerCase();
        return lower.contains("/../") || lower.contains("/./") || lower.endsWith("/..") || lower.endsWith("/.")
                || lower.contains("%2e") || lower.contains("%5c") || lower.indexOf('\\') >= 0;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (hasPathTricks(request.getRequestURI())) {
            reject(response, HttpServletResponse.SC_BAD_REQUEST, "Caminho de requisição inválido");
            return;
        }
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

        // O papel do token vale só até a conta mudar: conta desativada perde a sessão e
        // promoção/rebaixamento feitos no painel valem sem esperar o token expirar.
        if (sessionGuard != null) {
            try {
                UserSessionGuard.State state = sessionGuard.lookup(claims.get("sub").asText()).orElse(null);
                if (state != null) {
                    if (!state.active()) {
                        reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Sessão inválida: conta inativa");
                        return;
                    }
                    if (state.role() != null && !state.role().isBlank()) {
                        role = state.role();
                    }
                }
            } catch (RuntimeException e) {
                logger.warn("Não foi possível conferir o estado da conta; mantendo o papel do token", e);
            }
        }

        if (request.getRequestURI().startsWith("/api/admin") && !"ADMIN".equalsIgnoreCase(role)) {
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
