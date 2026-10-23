package com.agilespace.backend.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Limite de requisições em memória (por usuário quando há JWT válido, senão por IP) — suficiente pra uma instância única (ver
 * docker-compose.yml/DEPLOYMENT.md; não há deploy horizontal hoje, um limitador distribuído
 * tipo Redis seria over-engineering nesse estágio). Duas faixas: uma bem mais apertada pra
 * /api/auth/** (login/registro são alvo natural de força bruta e enumeração de e-mail) e uma
 * geral pra /api/** como guarda-chuva básico contra abuso. Roda antes do
 * JwtAuthenticationFilter (ordem menor = mais cedo) pra rejeitar excesso sem gastar validação
 * de token.
 *
 * Os buckets por IP nunca são removidos do mapa; aceitável pra cardinalidade de IP esperada
 * de uma ferramenta interna — se isso crescer sem limite, precisa de expiração/LRU.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Duration REFILL_PERIOD = Duration.ofMinutes(1);
    private static final String PUBLIC_INVITE_PREFIX = "/api/public/invites/";

    // Padrões = comportamento anterior (10/min nas rotas de entrada, 300/min geral por IP).
    private int authCapacity = 10;
    private int generalCapacity = 300;
    private int userCapacity = 300;
    private int publicInviteCapacity = 300;
    private boolean perUserEnabled = true;

    @Value("${app.ratelimit.auth-per-ip-per-minute:10}")
    void setAuthCapacity(int v) { this.authCapacity = Math.max(1, v); }

    @Value("${app.ratelimit.general-per-ip-per-minute:300}")
    void setGeneralCapacity(int v) { this.generalCapacity = Math.max(1, v); }

    /** Requisições com JWT válido contam por id do usuário, não por IP. */
    @Value("${app.ratelimit.user-per-minute:300}")
    void setUserCapacity(int v) { this.userCapacity = Math.max(1, v); }

    /** Leitura pública do convite (/api/public/invites/**): por IP, em balde próprio. */
    @Value("${app.ratelimit.public-invite-per-ip-per-minute:300}")
    void setPublicInviteCapacity(int v) { this.publicInviteCapacity = Math.max(1, v); }

    /** false = tudo volta a contar só por IP (comportamento antigo). */
    @Value("${app.ratelimit.per-user-enabled:true}")
    void setPerUserEnabled(boolean v) { this.perUserEnabled = v; }

    private final JwtTokenUtil jwtTokenUtil;

    public RateLimitFilter() {
        this(null);
    }

    @Autowired
    public RateLimitFilter(JwtTokenUtil jwtTokenUtil) {
        this.jwtTokenUtil = jwtTokenUtil;
    }

    /**
     * Proxies reversos confiáveis (IPs/CIDRs, separados por vírgula; TRUSTED_PROXIES). Vazio = X-Forwarded-For é
     * ignorado e o limite vale por endereço da conexão. Atrás do Caddy, informe a rede dele, senão todo mundo
     * cai no mesmo balde (o do proxy).
     */
    private TrustedProxies proxies = new TrustedProxies("");

    @Value("${app.security.trusted-proxies:}")
    void setTrustedProxies(String config) {
        this.proxies = new TrustedProxies(config);
    }

    private final ConcurrentMap<String, Bucket> authBuckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Bucket> generalBuckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Bucket> userBuckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Bucket> inviteBuckets = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        Bucket bucket;
        if (isStrictAuthPath(uri)) {
            bucket = authBuckets.computeIfAbsent(clientIp(request), k -> newBucket(authCapacity));
        } else if (uri.startsWith(PUBLIC_INVITE_PREFIX)) {
            bucket = inviteBuckets.computeIfAbsent(clientIp(request), k -> newBucket(publicInviteCapacity));
        } else {
            String userId = authenticatedUserId(request);
            bucket = userId != null
                    ? userBuckets.computeIfAbsent(userId, k -> newBucket(userCapacity))
                    : generalBuckets.computeIfAbsent(clientIp(request), k -> newBucket(generalCapacity));
        }

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long retryAfterSeconds = probe.getNanosToWaitForRefill() / 1_000_000_000L + 1;
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\": \"Muitas requisições. Tente de novo em " + retryAfterSeconds + " s.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Só as rotas sem sessão e alvo de força bruta/enumeração entram na faixa apertada (10/min por IP).
     * /api/auth/me e /api/auth/switch-project exigem token válido e são chamadas a cada carregamento de
     * página: na faixa apertada, poucas navegações seguidas (ou um escritório inteiro atrás do mesmo IP)
     * recebiam 429 e eram mandadas ao login. Elas ficam na faixa geral.
     */
    private static final Set<String> STRICT_AUTH_PATHS = Set.of(
            "/api/auth/login", "/api/auth/register", "/api/auth/forgot-password",
            // reset-password é público (token adivinhável por força bruta); change-password confere a senha atual.
            "/api/auth/reset-password", "/api/auth/change-password");

    static boolean isStrictAuthPath(String uri) {
        if (uri == null) return false;
        String path = uri.length() > 1 && uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
        return STRICT_AUTH_PATHS.contains(path);
    }

    private static Bucket newBucket(int capacity) {
        Bandwidth limit = Bandwidth.classic(capacity, Refill.greedy(capacity, REFILL_PERIOD));
        return Bucket.builder().addLimit(limit).build();
    }

    /**
     * Id do usuário se o Bearer for um JWT válido (assinatura e validade conferidas, então um token
     * forjado cai no balde do IP e não cria baldes por usuário); null caso contrário.
     */
    private String authenticatedUserId(HttpServletRequest request) {
        if (!perUserEnabled || jwtTokenUtil == null) return null;
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return null;
        try {
            JsonNode claims = jwtTokenUtil.validateAndExtractClaims(header);
            return claims != null && claims.hasNonNull("sub") ? claims.get("sub").asText() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String clientIp(HttpServletRequest request) {
        return proxies.clientIp(request.getRemoteAddr(), request.getHeader("X-Forwarded-For"));
    }
}
