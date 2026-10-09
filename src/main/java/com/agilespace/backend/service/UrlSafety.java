package com.agilespace.backend.service;

import java.util.Locale;

/**
 * Links digitados no card/capa são abertos por outros participantes (window.open, iframe, href).
 * Esquemas que executam código ou embutem conteúdo (javascript:, data:, vbscript:, file:, blob:) são
 * descartados; o resto passa como veio. O frontend normaliza (https:// quando falta) antes de enviar.
 */
final class UrlSafety {

    private UrlSafety() {
    }

    static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        // Navegadores ignoram controle/espaço dentro do esquema ("java\tscript:").
        String compact = trimmed.replaceAll("[\\p{Cntrl}\\s]", "").toLowerCase(Locale.ROOT);
        if (compact.startsWith("javascript:") || compact.startsWith("data:") || compact.startsWith("vbscript:")
                || compact.startsWith("file:") || compact.startsWith("blob:")) {
            return null;
        }
        return trimmed;
    }
}
