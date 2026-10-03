package com.agilespace.backend.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Geração da chave crua e hash SHA-256 — extraído daqui pra não repetir o
 * mesmo código em cada controller que emite API key (ApiKeyAdminController,
 * ApiKeyController) e no filtro que valida (ApiKeyAuthenticationFilter).
 */
public final class ApiKeyHashing {

    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeyHashing() {
    }

    public static String generateRawKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "ask_" + HexFormat.of().formatHex(bytes);
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
