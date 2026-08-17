package com.agilespace.backend.security;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class EncryptionUtil {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private EncryptionUtil() {}

    /**
     * Deriva uma chave AES de 256 bits a partir de uma string secreta usando SHA-256.
     */
    public static SecretKey deriveKey(String secret) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = sha.digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Erro ao derivar chave AES-256", e);
        }
    }

    /**
     * Criptografa o texto simples usando AES-256-GCM.
     * Retorna a representação Base64 contendo [12 bytes IV] + [Ciphertext + Auth Tag].
     */
    public static String encrypt(String plainText, SecretKey key) {
        if (plainText == null || plainText.isEmpty()) {
            return plainText;
        }

        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec);

            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + cipherText.length);
            byteBuffer.put(iv);
            byteBuffer.put(cipherText);

            return Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception e) {
            throw new RuntimeException("Erro ao criptografar dados sensíveis", e);
        }
    }

    /**
     * Descriptografa o texto cifrado em Base64 usando AES-256-GCM.
     * Se falhar (por exemplo, dado legado não cifrado), retorna o texto original com segurança.
     */
    public static String decrypt(String cipherTextBase64, SecretKey key) {
        if (cipherTextBase64 == null || cipherTextBase64.isEmpty()) {
            return cipherTextBase64;
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(cipherTextBase64);
            if (decoded.length <= GCM_IV_LENGTH) {
                return cipherTextBase64;
            }

            ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byteBuffer.get(iv);

            byte[] cipherText = new byte[byteBuffer.remaining()];
            byteBuffer.get(cipherText);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec);

            byte[] plainTextBytes = cipher.doFinal(cipherText);
            return new String(plainTextBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Em caso de falha de descriptografia (ex: token salvo antes da migração em texto puro),
            // retorna a string original para manter retrocompatibilidade.
            return cipherTextBase64;
        }
    }
}
