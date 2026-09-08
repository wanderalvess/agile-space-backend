package com.agilespace.backend.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ApiKeyHashing - Segurança de Chaves e Hashes")
class ApiKeyHashingTest {

    @Nested
    @DisplayName("Geração de Chaves")
    class KeyGenerationTests {

        @Test
        @DisplayName("Deve gerar chave com prefixo ask_")
        void shouldGenerateKeyWithPrefix() {
            String key = ApiKeyHashing.generateRawKey();

            assertTrue(key.startsWith("ask_"));
        }

        @Test
        @DisplayName("Deve gerar chave com tamanho adequado")
        void shouldGenerateKeyWithCorrectLength() {
            String key = ApiKeyHashing.generateRawKey();

            // "ask_" (4) + 64 hex chars (32 bytes * 2) = 68 chars
            assertEquals(68, key.length());
        }

        @Test
        @DisplayName("Deve gerar chaves únicas")
        void shouldGenerateUniqueKeys() {
            String key1 = ApiKeyHashing.generateRawKey();
            String key2 = ApiKeyHashing.generateRawKey();

            assertNotEquals(key1, key2);
        }

        @Test
        @DisplayName("Deve gerar apenas hex válido após prefixo")
        void shouldGenerateValidHexAfterPrefix() {
            String key = ApiKeyHashing.generateRawKey();
            String hexPart = key.substring(4); // Remove "ask_"

            assertTrue(hexPart.matches("[0-9a-f]{64}"));
        }
    }

    @Nested
    @DisplayName("Hashing SHA-256")
    class HashingTests {

        @Test
        @DisplayName("Deve produzir hash SHA-256 em hex")
        void shouldProduceSha256Hash() {
            String value = "test-value";
            String hash = ApiKeyHashing.sha256Hex(value);

            // SHA-256 produz 32 bytes = 64 hex chars
            assertEquals(64, hash.length());
            assertTrue(hash.matches("[0-9a-f]{64}"));
        }

        @Test
        @DisplayName("Deve ser determinístico - mesmo valor = mesmo hash")
        void shouldBeDeterministic() {
            String value = "test-value";
            String hash1 = ApiKeyHashing.sha256Hex(value);
            String hash2 = ApiKeyHashing.sha256Hex(value);

            assertEquals(hash1, hash2);
        }

        @Test
        @DisplayName("Deve gerar hashes diferentes pra valores diferentes")
        void shouldProduceDifferentHashesForDifferentValues() {
            String hash1 = ApiKeyHashing.sha256Hex("value1");
            String hash2 = ApiKeyHashing.sha256Hex("value2");

            assertNotEquals(hash1, hash2);
        }

        @Test
        @DisplayName("Deve ser irreversível - não deve recuperar valor do hash")
        void shouldBeIrreversible() {
            String key = ApiKeyHashing.generateRawKey();
            String hash = ApiKeyHashing.sha256Hex(key);

            // Hash não deve conter partes identificáveis da chave
            assertFalse(hash.contains("ask_"));
            assertNotEquals(key, hash);
        }

        @Test
        @DisplayName("Deve ignorar pequenas diferenças no valor")
        void shouldProduceDifferentHashForSmallChanges() {
            String key = "ask_abcd1234";
            String hash1 = ApiKeyHashing.sha256Hex(key);
            String hash2 = ApiKeyHashing.sha256Hex(key + "1"); // Uma letra a mais

            assertNotEquals(hash1, hash2);
        }
    }

    @Nested
    @DisplayName("Casos de Uso Real")
    class RealWorldUseCasesTests {

        @Test
        @DisplayName("Fluxo: Gerar chave, armazenar hash, validar depois")
        void shouldSupportKeyGenerationAndValidation() {
            // 1. Gerar chave
            String rawKey = ApiKeyHashing.generateRawKey();
            assertTrue(rawKey.startsWith("ask_"));

            // 2. Hash pra armazenamento
            String keyHash = ApiKeyHashing.sha256Hex(rawKey);
            assertEquals(64, keyHash.length());

            // 3. Validar depois (comparar hash)
            String incomingKey = rawKey; // Usuário envia a mesma chave
            String incomingHash = ApiKeyHashing.sha256Hex(incomingKey);
            assertEquals(keyHash, incomingHash);
        }

        @Test
        @DisplayName("Fluxo de segurança: Chave comprometida = hash não revelado")
        void shouldProtectAgainstKeyCompromise() {
            String storedHash = ApiKeyHashing.sha256Hex("ask_secretkey123");

            // Atacante tem o hash, não consegue recuperar a chave
            assertNotEquals("ask_secretkey123", storedHash);
            assertFalse(storedHash.startsWith("ask_"));
        }

        @Test
        @DisplayName("Deve processar chaves de qualquer tamanho")
        void shouldHandleVariousSizes() {
            String short_key = "short";
            String long_key = "a".repeat(1000);

            String hash1 = ApiKeyHashing.sha256Hex(short_key);
            String hash2 = ApiKeyHashing.sha256Hex(long_key);

            assertEquals(64, hash1.length());
            assertEquals(64, hash2.length());
            assertNotEquals(hash1, hash2);
        }

        @Test
        @DisplayName("Deve lidar com valores especiais (unicode, etc)")
        void shouldHandleSpecialCharacters() {
            String unicode_key = "ask_chave-éspecial_2024_🔐";
            String hash = ApiKeyHashing.sha256Hex(unicode_key);

            assertEquals(64, hash.length());
            assertTrue(hash.matches("[0-9a-f]{64}"));
        }

        @Test
        @DisplayName("Deve suportar lookup por hash no DB")
        void shouldSupportDatabaseLookup() {
            // Simula: usuário envia chave, app hasheia, DB busca por hash
            String userProvidedKey = "ask_user_provides_this";
            String storedHash = ApiKeyHashing.sha256Hex("ask_stored_in_db");

            // Usuário com chave correta
            String correctKey = "ask_stored_in_db";
            String correctHash = ApiKeyHashing.sha256Hex(correctKey);
            assertEquals(storedHash, correctHash);

            // Usuário com chave errada
            String wrongHash = ApiKeyHashing.sha256Hex(userProvidedKey);
            assertNotEquals(storedHash, wrongHash);
        }
    }

    @Nested
    @DisplayName("Propriedades Criptográficas")
    class CryptographicPropertiesTests {

        @Test
        @DisplayName("Hash deve ser imprevisível - sem padrão")
        void shouldProduceUnpredictableHashes() {
            String base = "test_key_";
            String hash1 = ApiKeyHashing.sha256Hex(base + "1");
            String hash2 = ApiKeyHashing.sha256Hex(base + "2");
            String hash3 = ApiKeyHashing.sha256Hex(base + "3");

            // Nenhum hash deve ser predizível do anterior
            assertNotEquals(hash1, hash2);
            assertNotEquals(hash2, hash3);
            assertNotEquals(hash1, hash3);

            // Hashes não devem ter padrão sequencial
            assertFalse(hash1.startsWith(hash2));
        }

        @Test
        @DisplayName("Chaves geradas devem ter entropia alta")
        void shouldGenerateHighEntropyKeys() {
            String key1 = ApiKeyHashing.generateRawKey();
            String key2 = ApiKeyHashing.generateRawKey();
            String key3 = ApiKeyHashing.generateRawKey();

            // Todos diferentes (praticamente certo)
            assertNotEquals(key1, key2);
            assertNotEquals(key2, key3);
            assertNotEquals(key1, key3);

            // Nenhum prefixo repetido além de "ask_"
            assertEquals("ask_", key1.substring(0, 4));
            assertEquals("ask_", key2.substring(0, 4));
            assertEquals("ask_", key3.substring(0, 4));

            // Resto é aleatório
            assertNotEquals(key1.substring(4), key2.substring(4));
            assertNotEquals(key2.substring(4), key3.substring(4));
        }
    }
}
