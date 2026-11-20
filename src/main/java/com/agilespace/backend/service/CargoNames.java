package com.agilespace.backend.service;

import com.agilespace.backend.domain.User;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;

/**
 * Nome canônico do cargo ({@code users.job_title}), o "cargo da administração" que vale de ponta a ponta.
 *
 * <p>Só duas famílias de grafia se juntam, por comparação EXATA (sem acento, sem caixa, sem espaços nas pontas; nunca
 * por "contém"): desenvolvimento e QA. Liderança (Agile Master, Tech Lead, Product Owner...), design, UX, SME,
 * Stakeholder e Observador NÃO são mapeados: passam como vieram (aparados). Vazio vira {@code null}.
 *
 * <p>A mesma regra existe em SQL na migração V95; mantenha as duas listas iguais.
 */
public final class CargoNames {

    public static final String DESENVOLVEDOR = "Desenvolvedor(a)";
    public static final String QA = "Analista de QA";

    /** Chave normalizada (minúscula, sem acento, espaços colapsados) -> nome canônico. */
    private static final Map<String, String> CANONICO = Map.ofEntries(
            Map.entry("developer", DESENVOLVEDOR),
            Map.entry("desenvolvedor", DESENVOLVEDOR),
            Map.entry("desenvolvedora", DESENVOLVEDOR),
            Map.entry("desenvolvedor(a)", DESENVOLVEDOR),
            Map.entry("dev", DESENVOLVEDOR),
            Map.entry("qa", QA),
            Map.entry("analista de qa", QA),
            Map.entry("qa engineer", QA),
            Map.entry("quality assurance", QA),
            Map.entry("analista de qualidade", QA));

    private CargoNames() {
    }

    /** Cargo canônico, o próprio valor aparado (se não for um dos sinônimos) ou {@code null} se vazio. */
    public static String canonical(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        String mapped = CANONICO.get(key(trimmed));
        return mapped != null ? mapped : trimmed;
    }

    /**
     * Grava {@code roleDoJira} (canonicalizado) em {@code user.jobTitle} SOMENTE se a pessoa ainda não tem cargo
     * (nulo/em branco). Nunca sobrescreve um cargo já definido (o da administração vale). Retorna se gravou.
     */
    public static boolean fillIfBlank(User user, String roleDoJira) {
        if (user == null) return false;
        if (user.getJobTitle() != null && !user.getJobTitle().isBlank()) return false;
        String canonical = canonical(roleDoJira);
        if (canonical == null) return false;
        user.setJobTitle(canonical);
        return true;
    }

    private static String key(String value) {
        String semAcento = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return semAcento.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
