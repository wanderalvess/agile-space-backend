package com.agilespace.backend.security;

import java.util.Locale;

/**
 * Chave de reversão da Etapa 4 do vínculo ({@code app.membership.lead-level}, env {@code APP_MEMBERSHIP_LEAD_LEVEL}):
 * o que o nível de conta {@code LEAD} ainda concede.
 *
 * <ul>
 *   <li>{@link #OFF} (padrão do application.yml, instalação nova): LEAD não concede NADA, é tratado como MEMBER.</li>
 *   <li>{@link #LEGACY}: o comportamento de antes (acesso total a squads, convite, facilitador do Poker, chaves de API...).</li>
 * </ul>
 *
 * Valor desconhecido cai em {@link #LEGACY}: errar a digitação nunca REMOVE acesso. Valor vazio vale o padrão ({@link #OFF}).
 */
public enum LeadLevel {
    OFF, LEGACY;

    public static LeadLevel parse(String raw) {
        if (raw == null || raw.isBlank()) return OFF;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return LEGACY;
        }
    }

    /** True quando o valor informado não é vazio nem um dos dois modos (para avisar no log de inicialização). */
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
