package com.agilespace.backend.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RateLimitFilter - limite por usuário autenticado")
class RateLimitPerUserTest {

    private static final String SECRET = "segredo-de-teste-com-mais-de-trinta-e-dois-bytes-ok";
    private JwtTokenUtil jwt;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        jwt = new JwtTokenUtil(SECRET, 3600);
        filter = new RateLimitFilter(jwt);
        filter.setUserCapacity(5);
        filter.setGeneralCapacity(3);
        filter.setPublicInviteCapacity(4);
        filter.setAuthCapacity(2);
    }

    private String bearer(String userId) {
        return "Bearer " + jwt.generateToken(userId, userId + "@x.com", "N", "MEMBER", null, null, null);
    }

    private int call(String uri, String ip, String authorization) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        req.setRemoteAddr(ip);
        if (authorization != null) req.addHeader("Authorization", authorization);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        if (res.getStatus() == 429) {
            assertTrue(res.getContentAsString().matches("(?s).*Tente de novo em [0-9]+ s.*"), res.getContentAsString());
            assertNotNull(res.getHeader("Retry-After"));
            // "message" é a chave padrão da API; "error" fica por compatibilidade.
            assertTrue(res.getContentAsString().contains("\"message\": \"Muitas requisições. Tente de novo em "), res.getContentAsString());
        }
        return res.getStatus();
    }

    @Test
    @DisplayName("Usuários A e B no mesmo IP não se bloqueiam")
    void usersBehindSameIpAreIndependent() throws Exception {
        String a = bearer("A"), b = bearer("B");
        for (int i = 0; i < 5; i++) assertEquals(200, call("/api/squads/1", "10.0.0.1", a));
        assertEquals(429, call("/api/squads/1", "10.0.0.1", a));
        assertEquals(200, call("/api/squads/1", "10.0.0.1", b));
    }

    @Test
    @DisplayName("Mesmo usuário em IPs diferentes divide o mesmo limite")
    void sameUserSharesBucketAcrossIps() throws Exception {
        String a = bearer("A");
        for (int i = 0; i < 5; i++) call("/api/squads/1", "10.0.0.1", a);
        assertEquals(429, call("/api/squads/1", "10.0.0.2", a));
    }

    @Test
    @DisplayName("Sem token válido continua por IP (token forjado também)")
    void unauthenticatedStaysPerIp() throws Exception {
        for (int i = 0; i < 3; i++) assertEquals(200, call("/api/squads/1", "10.0.0.3", null));
        assertEquals(429, call("/api/squads/1", "10.0.0.3", null));
        assertEquals(200, call("/api/squads/1", "10.0.0.4", null));
        for (int i = 0; i < 3; i++) call("/api/squads/1", "10.0.0.5", "Bearer a.b.c");
        assertEquals(429, call("/api/squads/1", "10.0.0.5", bearer("Z").substring(0, 40) + "x"));
    }

    @Test
    @DisplayName("Login continua por IP mesmo com token válido")
    void strictAuthPathStaysPerIp() throws Exception {
        String a = bearer("A");
        assertEquals(200, call("/api/auth/login", "10.0.0.6", a));
        assertEquals(200, call("/api/auth/login", "10.0.0.6", a));
        assertEquals(429, call("/api/auth/login", "10.0.0.6", a));
    }

    @Test
    @DisplayName("Convite público: balde próprio por IP, não consome o geral")
    void publicInviteHasOwnBucket() throws Exception {
        for (int i = 0; i < 4; i++) assertEquals(200, call("/api/public/invites/abc", "10.0.0.7", null));
        assertEquals(429, call("/api/public/invites/abc", "10.0.0.7", null));
        assertEquals(200, call("/api/squads/1", "10.0.0.7", null));
    }

    @Test
    @DisplayName("per-user-enabled=false volta ao limite só por IP")
    void perUserCanBeDisabled() throws Exception {
        filter.setPerUserEnabled(false);
        for (int i = 0; i < 3; i++) assertEquals(200, call("/api/squads/1", "10.0.0.8", bearer("A")));
        assertEquals(429, call("/api/squads/1", "10.0.0.8", bearer("B")));
    }

    @Test
    @DisplayName("WebSocket (/ws) não é filtrado")
    void websocketNotFiltered() throws Exception {
        for (int i = 0; i < 20; i++) assertEquals(200, call("/ws/user", "10.0.0.9", null));
    }
}
