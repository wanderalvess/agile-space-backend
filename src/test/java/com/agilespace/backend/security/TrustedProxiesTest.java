package com.agilespace.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("TrustedProxies / RateLimitFilter - IP do cliente atrás do proxy")
class TrustedProxiesTest {

    @Test
    @DisplayName("Sem proxy configurado, X-Forwarded-For é ignorado")
    void ignoresHeaderWithoutConfig() {
        TrustedProxies p = new TrustedProxies("");
        assertEquals("172.18.0.1", p.clientIp("172.18.0.1", "1.2.3.4"));
    }

    @Test
    @DisplayName("Conexão que não vem do proxy confiável: cabeçalho ignorado")
    void ignoresHeaderFromUntrustedPeer() {
        TrustedProxies p = new TrustedProxies("172.16.0.0/12");
        assertEquals("203.0.113.9", p.clientIp("203.0.113.9", "1.2.3.4"));
    }

    @Test
    @DisplayName("Vindo do proxy confiável vale o último IP não confiável, não o primeiro (forjável)")
    void usesLastUntrustedHop() {
        TrustedProxies p = new TrustedProxies("172.16.0.0/12");
        // o cliente mandou "6.6.6.6"; o Caddy acrescentou o IP real 198.51.100.7
        assertEquals("198.51.100.7", p.clientIp("172.18.0.2", "6.6.6.6, 198.51.100.7"));
        // cadeia de dois proxies confiáveis
        assertEquals("198.51.100.7", p.clientIp("172.18.0.2", "6.6.6.6, 198.51.100.7, 172.18.0.3"));
    }

    @Test
    @DisplayName("Valor que não é IP no cabeçalho: volta ao endereço da conexão")
    void garbageFallsBackToPeer() {
        TrustedProxies p = new TrustedProxies("172.16.0.0/12");
        assertEquals("172.18.0.2", p.clientIp("172.18.0.2", "evil.example.com"));
        assertEquals("172.18.0.2", p.clientIp("172.18.0.2", "198.51.100.7, <script>"));
    }

    @Test
    @DisplayName("CIDR, IP único e IPv6")
    void cidrMatching() {
        TrustedProxies p = new TrustedProxies("10.0.0.5, 192.168.1.0/24, fd00::/8, lixo, 300.1.1.1/8");
        assertTrue(p.isTrusted("10.0.0.5"));
        assertFalse(p.isTrusted("10.0.0.6"));
        assertTrue(p.isTrusted("192.168.1.200"));
        assertFalse(p.isTrusted("192.168.2.1"));
        assertTrue(p.isTrusted("fd12::1"));
        assertFalse(p.isTrusted("nome.com"));
    }

    @Test
    @DisplayName("Regressão: 15 logins com X-Forwarded-For forjado diferente a cada um ainda são barrados na 11ª")
    void spoofedHeaderDoesNotBypassAuthLimit() throws Exception {
        RateLimitFilter filter = new RateLimitFilter();
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getRequestURI()).thenReturn("/api/auth/login");
        when(request.getRemoteAddr()).thenReturn("203.0.113.50");
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        for (int i = 0; i < 15; i++) {
            when(request.getHeader("X-Forwarded-For")).thenReturn("10.9." + i + "." + (i + 1));
            filter.doFilterInternal(request, response, chain);
        }

        verify(chain, times(10)).doFilter(request, response);
        verify(response, times(5)).setStatus(429);
    }

    @Test
    @DisplayName("Com o proxy confiável configurado, cada cliente real tem o seu balde")
    void perClientBucketsBehindTrustedProxy() throws Exception {
        RateLimitFilter filter = new RateLimitFilter();
        filter.setTrustedProxies("172.16.0.0/12");
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getRequestURI()).thenReturn("/api/auth/login");
        when(request.getRemoteAddr()).thenReturn("172.18.0.2");
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        // cliente A estoura o próprio limite; o cliente B, atrás do mesmo proxy, segue livre
        when(request.getHeader("X-Forwarded-For")).thenReturn("1.1.1.1");
        for (int i = 0; i < 11; i++) filter.doFilterInternal(request, response, chain);
        verify(response, times(1)).setStatus(429);

        when(request.getHeader("X-Forwarded-For")).thenReturn("2.2.2.2");
        filter.doFilterInternal(request, response, chain);
        verify(response, times(1)).setStatus(429);
        verify(chain, times(11)).doFilter(request, response);
        verify(response, never()).sendError(any(Integer.class));
    }
}
