package com.agilespace.backend.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Única fonte da pergunta "este papel de conta concede privilégio de sistema?" (Etapa 4 do vínculo,
 * docs/vinculo-auditoria-e-plano.md). ADMIN sempre concede; LEAD só concede com {@code app.membership.lead-level=legacy}.
 *
 * <p>Os pontos que ainda não receberam a política (testes que montam o serviço à mão) usam {@link #LEGACY}, o
 * comportamento de antes; a instância injetada pelo Spring segue a chave.
 */
@Component
@Slf4j
public class LeadPolicy {

    /** Comportamento de antes da Etapa 4 (padrão das montagens manuais). */
    public static final LeadPolicy LEGACY = new LeadPolicy(LeadLevel.LEGACY);
    /** LEAD não concede nada. */
    public static final LeadPolicy OFF = new LeadPolicy(LeadLevel.OFF);

    private final LeadLevel level;

    @Autowired
    public LeadPolicy(@Value("${app.membership.lead-level:off}") String raw) {
        this.level = LeadLevel.parse(raw);
        if (LeadLevel.isUnknown(raw)) {
            log.warn("app.membership.lead-level inválido ({}): usando legacy (LEAD continua concedendo acesso; para desligar use off)",
                    sanitize(raw));
        }
        log.info("Vínculo: app.membership.lead-level={}", level.name().toLowerCase(Locale.ROOT));
    }

    public LeadPolicy(LeadLevel level) {
        this.level = level;
    }

    public LeadLevel level() {
        return level;
    }

    public boolean isLegacy() {
        return level == LeadLevel.LEGACY;
    }

    public boolean isOff() {
        return level == LeadLevel.OFF;
    }

    /** O papel de conta concede privilégio de sistema? ADMIN sempre; LEAD só em legacy. */
    public boolean isPrivileged(String role) {
        return "ADMIN".equalsIgnoreCase(role) || (isLegacy() && "LEAD".equalsIgnoreCase(role));
    }

    /** LEAD concedendo algo agora (só em legacy). */
    public boolean leadGrants(String role) {
        return isLegacy() && "LEAD".equalsIgnoreCase(role);
    }

    private static String sanitize(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("[^A-Za-z0-9._:-]", "?");
        return s.length() > 32 ? s.substring(0, 32) : s;
    }
}
