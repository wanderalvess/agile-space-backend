package com.agilespace.backend.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Utilitário padrão para emissão e validação de tokens JWT (HS256) sem dependências externas pesadas.
 */
@Component
@Slf4j
public class JwtTokenUtil {

    private final String secretKey;
    private final long expirationSeconds;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JwtTokenUtil(
            @Value("${app.security.jwt-secret:AgileSpaceMasterSecretKey2026TotvsRetailAndDistributionDefaultJwtSecret#HS256}") String secretKey,
            @Value("${app.security.jwt-expiration-seconds:86400}") long expirationSeconds) {
        this.secretKey = secretKey;
        this.expirationSeconds = expirationSeconds;
    }

    /**
     * Gera um token JWT com as claims do usuário e projetos associados.
     */
    public String generateToken(String userId, String email, String name, String role, String defaultProjectId, String segmentName, String tribeName) {
        try {
            long now = Instant.now().getEpochSecond();
            long exp = now + expirationSeconds;

            Map<String, Object> header = new HashMap<>();
            header.put("alg", "HS256");
            header.put("typ", "JWT");

            Map<String, Object> claims = new HashMap<>();
            claims.put("sub", userId);
            claims.put("email", email);
            claims.put("name", name);
            claims.put("role", role != null ? role : "MEMBER");
            claims.put("defaultProjectId", defaultProjectId != null ? defaultProjectId : "");
            claims.put("segmentName", segmentName != null ? segmentName : "");
            claims.put("tribeName", tribeName != null ? tribeName : "");
            claims.put("iat", now);
            claims.put("exp", exp);

            String encodedHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(header));
            String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(claims));

            String signature = sign(encodedHeader + "." + encodedPayload, secretKey);

            return encodedHeader + "." + encodedPayload + "." + signature;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao gerar token JWT", e);
        }
    }

    /**
     * Valida e extrai o payload JSON do token JWT. Retorna null se inválido ou expirado.
     */
    public JsonNode validateAndExtractClaims(String token) {
        if (token == null || token.isBlank()) return null;
        String cleanToken = token.startsWith("Bearer ") ? token.substring(7).trim() : token.trim();

        String[] parts = cleanToken.split("\\.");
        if (parts.length != 3) return null;

        String contentToSign = parts[0] + "." + parts[1];
        String expectedSignature = sign(contentToSign, secretKey);

        if (!expectedSignature.equals(parts[2])) {
            log.warn("Assinatura do JWT inválida");
            return null;
        }

        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode payload = objectMapper.readTree(payloadBytes);

            long exp = payload.has("exp") ? payload.get("exp").asLong() : 0;
            if (exp > 0 && exp < Instant.now().getEpochSecond()) {
                log.warn("Token JWT expirado");
                return null;
            }

            return payload;
        } catch (Exception e) {
            log.error("Erro ao decodificar payload JWT", e);
            return null;
        }
    }

    private String sign(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(rawHmac);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Erro ao assinar JWT com HMAC-SHA256", e);
        }
    }
}
