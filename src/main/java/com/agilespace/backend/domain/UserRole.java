package com.agilespace.backend.domain;

/**
 * Níveis de conta do sistema — os únicos valores válidos para {@link User#getRole()}. Distinto do cargo de negócio
 * autodeclarado ({@link User#getJobTitle()}) e dos papéis de squad (project_member_roles).
 *
 * <p>Etapa 4 do vínculo: LEAD deixou de ser nível de conta; quem lidera uma squad tem um vínculo de liderança
 * ({@code is_leadership}). Um "LEAD" ainda gravado no banco é lido como {@link #MEMBER} ({@link #parse}).
 */
public enum UserRole {
    ADMIN,
    MEMBER;

    /** Valor atribuível hoje (ADMIN ou MEMBER, sem caixa). */
    public static boolean isValid(String value) {
        if (value == null) return false;
        for (UserRole r : values()) {
            if (r.name().equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    /** O valor é o nível legado LEAD (só aceito para gravar com {@code app.membership.lead-level=legacy}). */
    public static boolean isLegacyLead(String value) {
        return value != null && "LEAD".equalsIgnoreCase(value.trim());
    }

    /** Leitura tolerante: ADMIN é ADMIN; LEAD legado, vazio e qualquer outro valor são MEMBER (nunca concede privilégio). */
    public static UserRole parse(String value) {
        return value != null && "ADMIN".equalsIgnoreCase(value.trim()) ? ADMIN : MEMBER;
    }
}
