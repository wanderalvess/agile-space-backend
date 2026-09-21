package com.agilespace.backend.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Limite de requisições por IP, em memória — suficiente pra uma instância única (ver
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

    private static final int AUTH_CAPACITY = 10;
    private static final Duration AUTH_REFILL_PERIOD = Duration.ofMinutes(1);

    private static final int GENERAL_CAPACITY = 300;
    private static final Duration GENERAL_REFILL_PERIOD = Duration.ofMinutes(1);

    private final ConcurrentMap<String, Bucket> authBuckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Bucket> generalBuckets = new ConcurrentHashMap<>();

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
        boolean isAuthPath = request.getRequestURI().startsWith("/api/auth/");
        String clientIp = clientIp(request);

        ConcurrentMap<String, Bucket> buckets = isAuthPath ? authBuckets : generalBuckets;
        Bucket bucket = buckets.computeIfAbsent(clientIp, ip -> isAuthPath ? newAuthBucket() : newGeneralBucket());

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long retryAfterSeconds = probe.getNanosToWaitForRefill() / 1_000_000_000L + 1;
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\": \"Muitas requisições. Tente novamente em instantes.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private Bucket newAuthBucket() {
        Bandwidth limit = Bandwidth.classic(AUTH_CAPACITY, Refill.greedy(AUTH_CAPACITY, AUTH_REFILL_PERIOD));
        return Bucket.builder().addLimit(limit).build();
    }

    private Bucket newGeneralBucket() {
        Bandwidth limit = Bandwidth.classic(GENERAL_CAPACITY, Refill.greedy(GENERAL_CAPACITY, GENERAL_REFILL_PERIOD));
        return Bucket.builder().addLimit(limit).build();
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
