package com.agilespace.backend.domain;

/**
 * Tiers de autorização do sistema — os únicos valores válidos para {@link User#getRole()}.
 * Distinto do cargo de negócio autodeclarado ({@link User#getJobTitle()}), que não carrega
 * nenhum significado de autorização.
 */
public enum UserRole {
    ADMIN,
    LEAD,
    MEMBER;

    public static boolean isValid(String value) {
        if (value == null) return false;
        for (UserRole r : values()) {
            if (r.name().equalsIgnoreCase(value)) return true;
        }
        return false;
    }
}
