package com.agilespace.backend.security;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JwtTokenUtil - Emissão e validação de token")
class JwtTokenUtilTest {

    private static final String SECRET = "test-secret-with-at-least-32-bytes-000000";

    @Test
    @DisplayName("Recusa segredo curto ou ausente")
    void rejectsWeakSecret() {
        assertThrows(IllegalStateException.class, () -> new JwtTokenUtil("curto", 3600));
        assertThrows(IllegalStateException.class, () -> new JwtTokenUtil(null, 3600));
    }

    @Test
    @DisplayName("Valida token assinado com o mesmo segredo")
    void roundTrip() {
        JwtTokenUtil util = new JwtTokenUtil(SECRET, 3600);
        String token = util.generateToken("u1", "a@totvs.com.br", "A", "MEMBER", null, null, null);

        JsonNode claims = util.validateAndExtractClaims(token);

        assertNotNull(claims);
        assertEquals("u1", claims.get("sub").asText());
    }

    @Test
    @DisplayName("Rejeita token assinado com outro segredo (ex.: o antigo default público)")
    void rejectsTokenFromOtherSecret() {
        String forged = new JwtTokenUtil("another-secret-with-at-least-32-bytes-111", 3600)
                .generateToken("u1", "a@totvs.com.br", "A", "ADMIN", null, null, null);

        assertNull(new JwtTokenUtil(SECRET, 3600).validateAndExtractClaims(forged));
    }
}
