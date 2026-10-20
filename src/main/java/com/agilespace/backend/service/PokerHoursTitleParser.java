package com.agilespace.backend.service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê do título da issue pai as horas que o Poker cola no Jira: "(DEV 10h / QA 4h / UX 2h)".
 * <p>Espelho, no sentido inverso, de {@code formatEstimateCopy} do frontend ({@code src/lib/poker-utils.ts}): papéis
 * separados por "/", valor seguido do sufixo do baralho. Só o sufixo {@code h} vale como horas (o baralho de pontos
 * gera "SP" e isso não é hora). Papéis fora de DEV/QA (UX, DESIGNER, ARQUITETO...) são aceitos e ignorados.
 * Quando há mais de um grupo entre parênteses com horas, vale o ÚLTIMO (o Poker cola no fim do título).
 * Um grupo com horas, mas sem um dos papéis, devolve 0 para o papel que falta ("(DEV 8h)" = DEV 8, QA 0).
 */
public final class PokerHoursTitleParser {

    public record TitleHours(BigDecimal dev, BigDecimal qa) {}

    private static final Pattern GROUP = Pattern.compile("\\(([^()]*)\\)");
    private static final Pattern PART = Pattern.compile(
            "^\\s*([\\p{L}][\\p{L}\\p{N} _.-]*?)\\s+(\\d+(?:[.,]\\d+)?)\\s*h\\s*$", Pattern.CASE_INSENSITIVE);

    private PokerHoursTitleParser() {}

    /** Horas DEV/QA do título, ou null quando o título não tem o padrão do Poker em horas. */
    public static TitleHours parse(String summary) {
        if (summary == null || summary.isBlank()) return null;
        Matcher g = GROUP.matcher(summary);
        TitleHours last = null;
        while (g.find()) {
            TitleHours parsed = parseGroup(g.group(1));
            if (parsed != null) last = parsed;
        }
        return last;
    }

    private static TitleHours parseGroup(String inner) {
        String[] parts = inner.split("/");
        BigDecimal dev = null;
        BigDecimal qa = null;
        boolean any = false;
        for (String part : parts) {
            Matcher m = PART.matcher(part);
            if (!m.matches()) return null; // um pedaço fora do padrão invalida o grupo inteiro (ex.: "5 SP")
            any = true;
            String role = m.group(1).trim().toUpperCase(Locale.ROOT);
            BigDecimal value = new BigDecimal(m.group(2).replace(',', '.'));
            if (role.equals("DEV") || role.equals("DEVELOPER")) dev = value;
            else if (role.equals("QA")) qa = value;
        }
        if (!any || (dev == null && qa == null)) return null;
        return new TitleHours(dev != null ? dev : BigDecimal.ZERO, qa != null ? qa : BigDecimal.ZERO);
    }
}
