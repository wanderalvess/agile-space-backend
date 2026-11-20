package com.agilespace.backend.domain;

import java.util.Locale;
import java.util.Set;

/**
 * De onde veio uma linha de {@code project_member_roles} (coluna {@code source}). Valor nulo = LEGADO: linha criada
 * antes da coluna existir; não se sabe a origem e ela é tratada como "do Jira, mas com cuidado" (ver {@link #protectedFromImport}).
 */
public final class MembershipSource {

    /** Veio da importação do Jira (Profields / API padrão). O Jira é a autoridade sobre o papel dela. */
    public static final String JIRA = "JIRA";
    /** Cadastrada à mão por quem gerencia o time (SquadTeamService) ou fundador de equipe manual. Papel é decisão humana. */
    public static final String MANUAL = "MANUAL";
    /** Criada pelo aceite de um convite. Papel é o do convite. */
    public static final String INVITE = "INVITE";
    /** Pessoa se reconheceu numa linha do roster ("Sou eu") ou entrou por join. */
    public static final String CLAIM = "CLAIM";
    /** Vínculo conta -> linha decidido por quem aprova (pedido de vínculo). */
    public static final String APPROVED = "APPROVED";
    /** user_id preenchido pela migração V75 (casamento único por e-mail). Reversível: source = 'BACKFILL'. */
    public static final String BACKFILL = "BACKFILL";
    /** Linha criada por migração de dados (V75, V77...). */
    public static final String MIGRATION = "MIGRATION";

    /** Origens que a reimportação do Jira NUNCA apaga (inclusive liderança). */
    private static final Set<String> PROTECTED = Set.of(MANUAL, INVITE, CLAIM, APPROVED, MIGRATION);

    /**
     * Origens cujo PAPEL nasceu de uma decisão humana: a reimportação não reescreve papel/liderança delas (a pessoa
     * ganha, no máximo, uma linha extra do Jira, e a liderança vence na hora de resolver). CLAIM, APPROVED e BACKFILL
     * não entram: são linhas do roster do Jira que só ganharam uma conta, então o papel continua seguindo o Jira.
     */
    private static final Set<String> ROLE_IS_HUMAN_DECISION = Set.of(MANUAL, INVITE, MIGRATION);

    private MembershipSource() {}

    public static String normalize(String source) {
        return source == null || source.isBlank() ? null : source.trim().toUpperCase(Locale.ROOT);
    }

    /** Linha que o Jira "possui": legado (nulo), JIRA ou BACKFILL. É a única classe que a reimportação pode remover. */
    public static boolean importOwned(String source) {
        String s = normalize(source);
        return s == null || JIRA.equals(s) || BACKFILL.equals(s);
    }

    /** A reimportação não pode remover esta linha só porque o Jira deixou de listá-la: origem protegida ou conta ligada (user_id). */
    public static boolean protectedFromImport(String source, String userId) {
        if (userId != null && !userId.isBlank()) return true;
        String s = normalize(source);
        return s != null && PROTECTED.contains(s);
    }

    /** O papel da linha é decisão humana (a reimportação só atualiza nome/foto/identidade, não papel nem liderança). */
    public static boolean roleIsHumanDecision(String source) {
        String s = normalize(source);
        return s != null && ROLE_IS_HUMAN_DECISION.contains(s);
    }

    /**
     * Origem depois de ligar uma conta a uma linha existente. Quem foi decidido por pessoa (MANUAL, INVITE, MIGRATION)
     * mantém a origem; linha legado/JIRA/BACKFILL passa a registrar como foi ligada.
     */
    public static String afterLink(String current, String linkSource) {
        String s = normalize(current);
        if (s == null || JIRA.equals(s) || BACKFILL.equals(s)) return linkSource;
        return s;
    }
}
