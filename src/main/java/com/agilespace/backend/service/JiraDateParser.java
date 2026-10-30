package com.agilespace.backend.service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Normaliza o valor de um campo de data/data-hora do Jira para o que o cronograma guarda:
 * "YYYY-MM-DD" (campo só de data) ou ISO-8601 com offset, sem fração (ex. 2026-10-20T09:00:00-03:00).
 */
public final class JiraDateParser {

    private static final ZoneId BR_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final Pattern DATE_ONLY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    // Jira Server manda o offset sem dois-pontos ("-0300"); o parser ISO exige "-03:00".
    private static final Pattern OFFSET_NO_COLON = Pattern.compile("([+-]\\d{2})(\\d{2})$");
    private static final DateTimeFormatter OUT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    private JiraDateParser() {}

    /** Devolve "" quando vazio ou irreconhecível. */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        if (DATE_ONLY.matcher(s).matches()) return s;
        if (s.length() < 10 || !DATE_ONLY.matcher(s.substring(0, 10)).matches()) return "";
        if (s.charAt(10) != 'T') return s.substring(0, 10);

        String iso = OFFSET_NO_COLON.matcher(s).replaceFirst("$1:$2");
        try {
            return OUT.format(OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        } catch (DateTimeParseException ignored) {
            // sem offset: o Jira do time é do Brasil
        }
        try {
            LocalDateTime ldt = LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return OUT.format(ldt.atZone(BR_ZONE));
        } catch (DateTimeParseException ignored) {
            return s.substring(0, 10);
        }
    }
}
