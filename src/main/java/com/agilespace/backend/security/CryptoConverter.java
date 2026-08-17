package com.agilespace.backend.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;

@Converter
@Component
public class CryptoConverter implements AttributeConverter<String, String> {

    private static SecretKey secretKey;

    public CryptoConverter() {
        if (secretKey == null) {
            String defaultSecret = System.getenv().getOrDefault("APP_ENCRYPTION_SECRET", "AgileSpaceSecureMasterKey2026Default#AES256GCMKey");
            secretKey = EncryptionUtil.deriveKey(defaultSecret);
        }
    }

    @Value("${app.security.encryption-key:AgileSpaceSecureMasterKey2026Default#AES256GCMKey}")
    public void setSecretKeyFromProperty(String secret) {
        if (secret != null && !secret.isBlank()) {
            secretKey = EncryptionUtil.deriveKey(secret);
        }
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isBlank()) {
            return attribute;
        }
        return EncryptionUtil.encrypt(attribute, secretKey);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return dbData;
        }
        return EncryptionUtil.decrypt(dbData, secretKey);
    }
}
