package com.agilespace.backend.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CryptoConverter - Conversor JPA para Criptografia de Campos Sensíveis (Tokens PAT Jira / Senhas)")
class CryptoConverterTest {

    private CryptoConverter converter;

    @BeforeEach
    void setUp() {
        converter = new CryptoConverter();
        converter.setSecretKeyFromProperty("CustomTestMasterEncryptionKeyForUnitTesting#2026");
    }

    @Nested
    @DisplayName("Ciclo de Criptografia e Decriptografia")
    class EncryptionDecryptionCycleTests {

        @Test
        @DisplayName("Deve cifrar e decifrar com sucesso token PAT preservando conteúdo original")
        void shouldEncryptAndDecryptSuccessfully() {
            String originalToken = "jira_pat_sec_1234567890abcdef_XYZ";

            String encrypted = converter.convertToDatabaseColumn(originalToken);

            assertNotNull(encrypted);
            assertNotEquals(originalToken, encrypted, "Texto criptografado deve diferir do original");
            assertTrue(encrypted.length() > originalToken.length(), "Texto cifrado contém IV e autenticação GCM");

            String decrypted = converter.convertToEntityAttribute(encrypted);

            assertEquals(originalToken, decrypted, "Texto decifrado deve ser exatamente igual ao original");
        }

        @Test
        @DisplayName("Deve gerar cifras diferentes para o mesmo texto plano devido ao IV aleatório do AES-GCM")
        void shouldProduceDifferentCiphertextsForSameInputDueToRandomIV() {
            String originalToken = "same-secret-token-repeated";

            String cipher1 = converter.convertToDatabaseColumn(originalToken);
            String cipher2 = converter.convertToDatabaseColumn(originalToken);

            assertNotEquals(cipher1, cipher2, "IV aleatório garante que cifras idênticas nunca se repitam");
            assertEquals(originalToken, converter.convertToEntityAttribute(cipher1));
            assertEquals(originalToken, converter.convertToEntityAttribute(cipher2));
        }
    }

    @Nested
    @DisplayName("Tratamento de Casos de Borda e Compatibilidade")
    class EdgeCasesAndBackwardCompatibilityTests {

        @Test
        @DisplayName("Deve retornar null e vazio adequadamente sem falhas")
        void shouldHandleNullAndEmptyGracefully() {
            assertNull(converter.convertToDatabaseColumn(null));
            assertNull(converter.convertToEntityAttribute(null));
            assertEquals("", converter.convertToDatabaseColumn(""));
            assertEquals("", converter.convertToEntityAttribute(""));
        }

        @Test
        @DisplayName("Deve permitir fallback seguro para registros legados que ainda estejam em texto puro no banco")
        void shouldHandleLegacyPlaintextGracefully() {
            String legacyPlainText = "unencrypted-legacy-token-already-in-db";

            String result = converter.convertToEntityAttribute(legacyPlainText);

            assertEquals(legacyPlainText, result, "Se falhar decriptação (legado), deve retornar o texto original");
        }
    }
}
