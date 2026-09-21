package com.agilespace.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitFilter - Limite de requisições por IP")
class RateLimitFilterTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
    }

    @Test
    @DisplayName("Ignora rotas fora de /api/")
    void shouldSkipNonApiPaths() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/swagger-ui.html");

        assertEquals(true, invokeShouldNotFilter(request));
    }

    @Test
    @DisplayName("Permite requisições dentro do limite geral")
    void shouldAllowRequestsWithinGeneralLimit() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/squads/SQ1");
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(429);
    }

    @Test
    @DisplayName("Bloqueia com 429 ao estourar o limite apertado de /api/auth/**")
    void shouldRejectWhenAuthLimitExceeded() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/auth/login");
        when(request.getRemoteAddr()).thenReturn("10.0.0.2");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);

        StringWriterHolder writer = new StringWriterHolder();
        when(response.getWriter()).thenReturn(writer.printWriter);

        // Capacidade de /api/auth/** é 10/min — a 11ª chamada no mesmo minuto deve ser barrada.
        for (int i = 0; i < 10; i++) {
            filter.doFilterInternal(request, response, filterChain);
        }
        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, times(10)).doFilter(request, response);
        verify(response, times(1)).setStatus(429);
        verify(response, times(1)).setHeader(eq("Retry-After"), any(String.class));
    }

    @Test
    @DisplayName("IPs diferentes têm buckets independentes")
    void shouldTrackSeparateBucketsPerIp() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/auth/login");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);

        when(request.getRemoteAddr()).thenReturn("10.0.0.3");
        for (int i = 0; i < 10; i++) {
            filter.doFilterInternal(request, response, filterChain);
        }

        // Outro IP não deve estar afetado pelo consumo do primeiro.
        when(request.getRemoteAddr()).thenReturn("10.0.0.4");
        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, times(11)).doFilter(request, response);
        verify(response, never()).setStatus(429);
    }

    @Test
    @DisplayName("Usa o primeiro IP de X-Forwarded-For quando presente")
    void shouldUseForwardedForHeaderWhenPresent() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/auth/login");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5, 10.0.0.1");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        verify(request, never()).getRemoteAddr();
    }

    private boolean invokeShouldNotFilter(HttpServletRequest req) throws Exception {
        var method = RateLimitFilter.class.getDeclaredMethod("shouldNotFilter", HttpServletRequest.class);
        method.setAccessible(true);
        return (boolean) method.invoke(filter, req);
    }

    private static class StringWriterHolder {
        final java.io.StringWriter stringWriter = new java.io.StringWriter();
        final PrintWriter printWriter = new PrintWriter(stringWriter);
    }
}
