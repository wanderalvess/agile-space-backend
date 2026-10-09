package com.agilespace.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("JwtAuthenticationFilter - rotas públicas estritas, caminhos suspeitos e estado vivo da conta")
class JwtAuthenticationFilterSessionTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JwtTokenUtil tokenUtil = mock(JwtTokenUtil.class);
    private final UserSessionGuard guard = mock(UserSessionGuard.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenUtil, guard);

    private void tokenFor(String sub, String role) throws Exception {
        when(tokenUtil.validateAndExtractClaims(anyString())).thenReturn(
                mapper.readTree("{\"sub\":\"" + sub + "\",\"role\":\"" + role + "\",\"email\":\"a@b.c\"}"));
    }

    private MockHttpServletResponse call(String uri, String bearer, MockHttpServletRequest[] seen) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (bearer != null) request.addHeader("Authorization", "Bearer " + bearer);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        if (seen != null) seen[0] = request;
        return response;
    }

    @Test
    @DisplayName("Prefixo público só vale como segmento inteiro")
    void publicPrefixMustBeWholeSegment() throws Exception {
        assertEquals(200, call("/api/public/system-config", null, null).getStatus());
        assertEquals(200, call("/api/changelog", null, null).getStatus());
        assertEquals(200, call("/api/changelog/latest", null, null).getStatus());
        assertEquals(401, call("/api/publicidade", null, null).getStatus());
        assertEquals(401, call("/api/v1abc", null, null).getStatus());
        assertEquals(401, call("/api/changelogs", null, null).getStatus());
        assertEquals(401, call("/api/auth/login-extra", null, null).getStatus());
        assertEquals(401, call("/api/auth/me", null, null).getStatus());
    }

    @Test
    @DisplayName("Caminho com /.. ou codificado é recusado, mesmo sob prefixo público ou fora de /api")
    void pathTricksAreRejected() throws Exception {
        assertEquals(400, call("/api/public/../users", null, null).getStatus());
        assertEquals(400, call("/x/../api/users", null, null).getStatus());
        assertEquals(400, call("/api/public/%2e%2e/users", null, null).getStatus());
        assertEquals(400, call("/api/public/..\\users", null, null).getStatus());
        assertEquals(200, call("/api/public/prompt-hub/items", null, null).getStatus());
        assertEquals(200, call("/_next/static/chunks/app.js", null, null).getStatus());
    }

    @Test
    @DisplayName("Conta inativa perde a sessão mesmo com token válido")
    void inactiveAccountIsRejected() throws Exception {
        tokenFor("u1", "MEMBER");
        when(guard.lookup("u1")).thenReturn(Optional.of(new UserSessionGuard.State(false, "MEMBER")));
        assertEquals(401, call("/api/projects", "t", null).getStatus());
    }

    @Test
    @DisplayName("Admin rebaixado perde /api/admin e o papel do banco vale no request")
    void demotedAdminLosesAccess() throws Exception {
        tokenFor("u1", "ADMIN");
        when(guard.lookup("u1")).thenReturn(Optional.of(new UserSessionGuard.State(true, "MEMBER")));
        assertEquals(403, call("/api/admin/stats", "t", null).getStatus());

        MockHttpServletRequest[] seen = new MockHttpServletRequest[1];
        assertEquals(200, call("/api/projects", "t", seen).getStatus());
        assertEquals("MEMBER", seen[0].getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE));
    }

    @Test
    @DisplayName("Promovido no painel ganha /api/admin sem esperar o token expirar")
    void promotedUserGainsAccess() throws Exception {
        tokenFor("u1", "MEMBER");
        when(guard.lookup("u1")).thenReturn(Optional.of(new UserSessionGuard.State(true, "ADMIN")));
        assertEquals(200, call("/api/admin/stats", "t", null).getStatus());
    }

    @Test
    @DisplayName("Usuário sem linha no banco ou falha na consulta mantém o papel do token")
    void unknownUserOrLookupFailureKeepsTokenRole() throws Exception {
        tokenFor("u1", "MEMBER");
        when(guard.lookup("u1")).thenReturn(Optional.empty());
        assertEquals(200, call("/api/projects", "t", null).getStatus());

        when(guard.lookup("u1")).thenThrow(new RuntimeException("db fora"));
        assertEquals(200, call("/api/projects", "t", null).getStatus());
        assertEquals(403, call("/api/admin/stats", "t", null).getStatus());
    }

    @Test
    @DisplayName("Sem token a rota protegida devolve 401")
    void missingTokenIs401() throws Exception {
        assertEquals(401, call("/api/projects", null, null).getStatus());
    }
}
