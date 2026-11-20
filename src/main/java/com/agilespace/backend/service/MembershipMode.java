package com.agilespace.backend.service;

import java.util.Locale;

/**
 * Chave de migração do vínculo (app.membership.mode): quem alimenta accessibleProjects/activeProject* da sessão.
 * LEGACY = só o resolvedor antigo (comportamento de antes); SHADOW = resposta do legado, novo cálculo em paralelo só
 * para comparar e registrar divergências; NEW = a resposta vem do {@link MembershipService}.
 */
public enum MembershipMode {
    LEGACY, SHADOW, NEW;

    /** Valor desconhecido cai em LEGACY (o comportamento anterior): errar de digitação nunca liga o modelo novo. */
    public static MembershipMode parse(String raw) {
        if (raw == null || raw.isBlank()) return SHADOW;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return LEGACY;
        }
    }
}
