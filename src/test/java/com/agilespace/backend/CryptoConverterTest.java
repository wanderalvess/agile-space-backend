package com.agilespace.backend;

import com.agilespace.backend.security.CryptoConverter;
import com.agilespace.backend.security.EncryptionUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;

import static org.junit.jupiter.api.Assertions.*;

public class CryptoConverterTest {

    private CryptoConverter converter;
    private SecretKey testKey;

    @BeforeEach
    void setUp() {
        converter = new CryptoConverter();
        converter.setSecretKeyFromProperty("CustomTestMasterEncryptionKeyForUnitTesting#2026");
        testKey = EncryptionUtil.deriveKey("CustomTestMasterEncryptionKeyForUnitTesting#2026");
    }

    @Test
    void testEncryptionAndDecryptionCycle() {
        String originalToken = "jira_pat_sec_1234567890abcdef_XYZ";

        String encrypted = converter.convertToDatabaseColumn(originalToken);

        assertNotNull(encrypted);
        assertNotEquals(originalToken, encrypted);
        assertTrue(encrypted.length() > originalToken.length());

        String decrypted = converter.convertToEntityAttribute(encrypted);

        assertEquals(originalToken, decrypted);
    }

    @Test
    void testNullAndEmptyHandling() {
        assertNull(converter.convertToDatabaseColumn(null));
        assertNull(converter.convertToEntityAttribute(null));
        assertEquals("", converter.convertToDatabaseColumn(""));
        assertEquals("", converter.convertToEntityAttribute(""));
    }

    @Test
    void testLegacyPlainTextGracefulFallback() {
        String legacyPlainText = "unencrypted-legacy-token-already-in-db";

        // Se uma string em texto puro existente no banco for lida, o conversor deve retornar sem estourar exception
        String result = converter.convertToEntityAttribute(legacyPlainText);

        assertEquals(legacyPlainText, result);
    }

    @Test
    void testRandomIVProducesDifferentCiphertext() {
        String originalToken = "same-token-repeated";

        String encrypted1 = converter.convertToDatabaseColumn(originalToken);
        String encrypted2 = converter.convertToDatabaseColumn(originalToken);

        assertNotEquals(encrypted1, encrypted2, "Due to random IV in AES-GCM, two encryptions of same text should produce different ciphertexts");
        assertEquals(originalToken, converter.convertToEntityAttribute(encrypted1));
        assertEquals(originalToken, converter.convertToEntityAttribute(encrypted2));
    }
}
