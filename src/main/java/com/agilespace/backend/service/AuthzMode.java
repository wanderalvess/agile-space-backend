package com.agilespace.backend.service;

import java.util.Locale;

/**
 * Chave de migração da AUTORIZAÇÃO por squad (app.membership.authz, env APP_MEMBERSHIP_AUTHZ). Etapa 2 do plano de
 * vínculo (docs/vinculo-auditoria-e-plano.md).
 *
 * <ul>
 *   <li>{@link #LEGACY} (padrão): a decisão é exatamente a de antes (users.squad_id, roster squad_members, cargo).</li>
 *   <li>{@link #SHADOW}: a decisão continua a do legado; a nova é calculada em paralelo e cada divergência vira o log
 *       {@code authz_divergence} e o contador {@code authz.divergence}.</li>
 *   <li>{@link #NEW}: a decisão é a nova (só vínculos de project_member_roles e ADMIN; LEAD só com app.membership.lead-level=legacy, Etapa 4).</li>
 * </ul>
 *
 * Diferente de {@link MembershipMode} (cujo padrão é SHADOW), aqui o padrão é LEGACY e qualquer valor desconhecido também
 * cai em LEGACY: errar de digitação nunca liga a regra nova, que pode tirar acesso.
 */
public enum AuthzMode {
    LEGACY, SHADOW, NEW;

    public static AuthzMode parse(String raw) {
        if (raw == null || raw.isBlank()) return LEGACY;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return LEGACY;
        }
    }

    /** True quando o valor informado não é vazio nem um dos três modos (para avisar no log de inicialização). */
    public static boolean isUnknown(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            valueOf(raw.trim().toUpperCase(Locale.ROOT));
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
