package com.agilespace.backend.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * Utilitário de hashing e validação segura de senhas utilizando PBKDF2 com HMAC-SHA256 e Salt.
 */
public final class PasswordUtil {

    private static final int ITERATIONS = 310000;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtil() {}

    /**
     * Gera o hash seguro da senha com salt aleatório.
     * Formato retornado: "pbkdf2:sha256:{iterations}:{saltBase64}:{hashBase64}"
     */
    public static String hashPassword(String password) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Senha não pode ser nula ou vazia");
        }

        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);

        byte[] hash = pbkdf2(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);

        return "pbkdf2:sha256:" + ITERATIONS + ":" +
                Base64.getEncoder().encodeToString(salt) + ":" +
                Base64.getEncoder().encodeToString(hash);
    }

    /**
     * Valida se a senha em texto plano confere com o hash armazenado.
     */
    public static boolean verifyPassword(String password, String storedHash) {
        if (password == null || storedHash == null || storedHash.isEmpty()) {
            return false;
        }

        String[] parts = storedHash.split(":");
        if (parts.length != 5 || !parts[0].equals("pbkdf2") || !parts[1].equals("sha256")) {
            // Se o hash estiver em formato não suportado, rejeita
            return false;
        }

        try {
            int iterations = Integer.parseInt(parts[2]);
            byte[] salt = Base64.getDecoder().decode(parts[3]);
            byte[] hash = Base64.getDecoder().decode(parts[4]);

            byte[] testHash = pbkdf2(password.toCharArray(), salt, iterations, hash.length * 8);

            return slowEquals(hash, testHash);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyLength) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyLength);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return skf.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Erro ao processar algoritmo de hashing PBKDF2", e);
        }
    }

    /**
     * Comparação em tempo constante para evitar ataques de timing.
     */
    private static boolean slowEquals(byte[] a, byte[] b) {
        int diff = a.length ^ b.length;
        for (int i = 0; i < a.length && i < b.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}
