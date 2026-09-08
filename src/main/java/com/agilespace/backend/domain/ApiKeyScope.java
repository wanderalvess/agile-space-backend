package com.agilespace.backend.domain;

/**
 * Escopos válidos para {@link ApiKey#getScopes()}. Cada valor autoriza uma operação
 * específica (REST e/ou MCP) — ver enforcement em ApiKeyAuthenticationFilter (REST)
 * e ApiKeyContext (MCP). Uma chave sem nenhum escopo listado aqui não é
 * necessariamente "sem poder": ver ApiKey.hasFullAccessGrandfathered() para o caso
 * de chaves criadas antes deste enum existir.
 */
public enum ApiKeyScope {
    KNOWLEDGE_READ,
    KNOWLEDGE_WRITE,
    SQUAD_READ,
    PROMPTHUB_READ,
    POKER_READ,
    POKER_WRITE;

    public static boolean isValid(String value) {
        if (value == null) return false;
        for (ApiKeyScope s : values()) {
            if (s.name().equalsIgnoreCase(value)) return true;
        }
        return false;
    }
}
