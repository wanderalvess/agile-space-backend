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
import java.security.MessageDigest;
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

    static final int MIN_SECRET_BYTES = 32;

    private final String secretKey;
    private final long expirationSeconds;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Teto absoluto de uma sessão renovada: depois disso é preciso entrar de novo, por mais que se renove. */
    @Value("${app.security.jwt-max-session-seconds:604800}")
    private long maxSessionSeconds = 604800L;

    public JwtTokenUtil(
            @Value("${app.security.jwt-secret}") String secretKey,
            @Value("${app.security.jwt-expiration-seconds:86400}") long expirationSeconds) {
        // Sem fallback no código: o segredo vem de APP_JWT_SECRET (application.yml tem um default
        // só pra dev; application-prod.yml não tem nenhum). Quem conhece o segredo forja token de
        // qualquer usuário, inclusive ADMIN.
        if (secretKey == null || secretKey.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "app.security.jwt-secret (APP_JWT_SECRET) precisa ter pelo menos " + MIN_SECRET_BYTES
                            + " bytes. Gere com: openssl rand -base64 48");
        }
        this.secretKey = secretKey;
        this.expirationSeconds = expirationSeconds;
    }

    /**
     * Gera um token JWT com as claims do usuário e projetos associados.
     */
    public String generateToken(String userId, String email, String name, String role, String defaultProjectId, String segmentName, String tribeName) {
        return generateToken(userId, email, name, role, defaultProjectId, segmentName, tribeName, Instant.now().getEpochSecond());
    }

    /**
     * Igual ao anterior, mas preserva o instante do login original (claim "authAt") quando o token é uma
     * renovação, para o teto de {@code maxSessionSeconds} valer sobre a sessão inteira.
     */
    public String generateToken(String userId, String email, String name, String role, String defaultProjectId, String segmentName, String tribeName, long authAt) {
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
            claims.put("authAt", authAt);
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

        if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            log.warn("Assinatura do JWT inválida");
            return null;
        }

        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode payload = objectMapper.readTree(payloadBytes);

            // Token sem "exp" nunca é emitido por generateToken; aceitá-lo criaria sessão eterna.
            long exp = payload.has("exp") ? payload.get("exp").asLong() : 0;
            if (exp <= 0 || exp < Instant.now().getEpochSecond()) {
                log.warn("Token JWT expirado ou sem validade");
                return null;
            }

            return payload;
        } catch (Exception e) {
            log.error("Erro ao decodificar payload JWT", e);
            return null;
        }
    }

    /** Instante (epoch, s) do login que originou a sessão; tokens antigos sem a claim usam o próprio iat. */
    public long authAtOf(JsonNode claims) {
        if (claims == null) return 0L;
        if (claims.hasNonNull("authAt")) return claims.get("authAt").asLong();
        return claims.hasNonNull("iat") ? claims.get("iat").asLong() : 0L;
    }

    public long getMaxSessionSeconds() {
        return maxSessionSeconds;
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
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
