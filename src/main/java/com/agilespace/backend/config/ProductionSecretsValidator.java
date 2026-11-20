package com.agilespace.backend.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Barra o boot no profile prod quando algum segredo está ausente, vazio ou com o valor de dev.
 *
 * Existe porque placeholders do Spring só caem no default quando a variável NÃO existe: o
 * docker-compose repassa "${VAR:-}", então a variável chega definida porém vazia e passa direto
 * pelo default do application-prod.yml (ex.: allowed-email-domain virava "" e liberava cadastro
 * de qualquer e-mail). Falhar alto no boot é melhor que subir inseguro em silêncio.
 */
@Component
@Profile("prod")
public class ProductionSecretsValidator {

    static final String DEV_JWT_SECRET = "AgileSpaceDevJwtSecret2026LocalOnly#NeverUseInProduction";
    static final String LEGACY_JWT_SECRET = "AgileSpaceMasterSecretKey2026EnterpriseProductionDefaultJwtSecret#HS256";
    static final String DEV_ENCRYPTION_KEY = "AgileSpaceDevMasterKey2026LocalOnly#AES";

    private final String jwtSecret;
    private final String encryptionKey;
    private final String allowedEmailDomain;

    public ProductionSecretsValidator(
            @Value("${app.security.jwt-secret:}") String jwtSecret,
            @Value("${app.security.encryption-key:}") String encryptionKey,
            @Value("${app.security.allowed-email-domain:}") String allowedEmailDomain) {
        this.jwtSecret = jwtSecret;
        this.encryptionKey = encryptionKey;
        this.allowedEmailDomain = allowedEmailDomain;
    }

    @PostConstruct
    void validate() {
        List<String> problems = new ArrayList<>();

        if (isBlank(jwtSecret) || DEV_JWT_SECRET.equals(jwtSecret) || LEGACY_JWT_SECRET.equals(jwtSecret)) {
            problems.add("APP_JWT_SECRET ausente ou com valor de dev (gere com: openssl rand -base64 48)");
        }
        if (isBlank(encryptionKey) || DEV_ENCRYPTION_KEY.equals(encryptionKey)) {
            problems.add("APP_ENCRYPTION_SECRET ausente ou com valor de dev (gere com: openssl rand -base64 32)");
        }
        if (isBlank(allowedEmailDomain)) {
            problems.add("ALLOWED_EMAIL_DOMAIN vazio: o cadastro ficaria aberto a qualquer e-mail (use totvs.com.br,ext.totvs.com.br)");
        } else {
            for (String domain : allowedEmailDomain.split(",", -1)) {
                String d = domain.trim();
                if (d.isEmpty() || d.contains("@") || d.contains(" ") || !d.contains(".")) {
                    problems.add("ALLOWED_EMAIL_DOMAIN inválido (\"" + d + "\"): use domínios separados por vírgula, sem @ (ex.: totvs.com.br,ext.totvs.com.br)");
                    break;
                }
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Configuração de produção insegura, recusando subir:\n - " + String.join("\n - ", problems));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
