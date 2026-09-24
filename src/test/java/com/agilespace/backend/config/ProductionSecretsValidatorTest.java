package com.agilespace.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProductionSecretsValidator - Barra boot de prod com segredos de dev")
class ProductionSecretsValidatorTest {

    private static final String STRONG_JWT = "k3Yq9vT2pL8xW4nB6mR1sD7fH0jC5gA9eU2iO4yZ8tX6";
    private static final String STRONG_AES = "Zp4Rt8Wm2Qx6Ln0Vb3Hc7Jd1Kf5Gs9Ay";

    @Test
    @DisplayName("Sobe com segredos fortes e domínio definido")
    void acceptsValidConfig() {
        assertDoesNotThrow(() -> new ProductionSecretsValidator(STRONG_JWT, STRONG_AES, "totvs.com.br").validate());
    }

    @Test
    @DisplayName("Recusa JWT secret de dev ou o antigo default do código")
    void rejectsDevJwtSecret() {
        assertThrows(IllegalStateException.class, () -> new ProductionSecretsValidator(
                ProductionSecretsValidator.DEV_JWT_SECRET, STRONG_AES, "totvs.com.br").validate());
        assertThrows(IllegalStateException.class, () -> new ProductionSecretsValidator(
                ProductionSecretsValidator.LEGACY_JWT_SECRET, STRONG_AES, "totvs.com.br").validate());
    }

    @Test
    @DisplayName("Recusa chave AES de dev")
    void rejectsDevEncryptionKey() {
        assertThrows(IllegalStateException.class, () -> new ProductionSecretsValidator(
                STRONG_JWT, ProductionSecretsValidator.DEV_ENCRYPTION_KEY, "totvs.com.br").validate());
    }

    @Test
    @DisplayName("Recusa domínio de e-mail vazio (variável do compose definida porém vazia)")
    void rejectsBlankEmailDomain() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ProductionSecretsValidator(STRONG_JWT, STRONG_AES, "").validate());
        assertTrue(ex.getMessage().contains("ALLOWED_EMAIL_DOMAIN"));
    }

    @Test
    @DisplayName("Lista todos os problemas de uma vez")
    void reportsAllProblems() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ProductionSecretsValidator("", "", " ").validate());
        assertTrue(ex.getMessage().contains("APP_JWT_SECRET"));
        assertTrue(ex.getMessage().contains("APP_ENCRYPTION_SECRET"));
        assertTrue(ex.getMessage().contains("ALLOWED_EMAIL_DOMAIN"));
    }
}
