package com.agilespace.backend.service;

/**
 * As regras de autorização por squad como FUNÇÕES PURAS sobre fatos já levantados (Etapa 2 do vínculo), para que o
 * modo sombra (motivo da divergência) e o relatório {@code authz-diff}/{@code WOULD_LOSE_ACCESS} (todos os usuários x
 * squads, sem uma consulta por par) usem exatamente a mesma definição. As decisões de produção do
 * {@link SquadAccessService} no modo legado continuam sendo o código antigo, intocado; um teste cruza as duas.
 *
 * <p>Fatos (por usuário e squad):
 * <ul>
 *   <li>{@code sessionSquad}: users.squad_id / default_project_id aponta para a squad (com o alias DDWMISSI/MISSI);</li>
 *   <li>{@code roster}: está em squad_members por vaga reivindicada, e-mail ou conta do Jira (nome nunca conta);</li>
 *   <li>{@code jobTitleLeadership}: users.job_title é um cargo de liderança (autodeclarado);</li>
 *   <li>{@code legacyLinked}/{@code legacyLeader}: o resolvedor antigo devolve a squad (e com liderança);</li>
 *   <li>{@code newLinked}/{@code newLeader}: o {@link MembershipService} devolve a squad (e com liderança);</li>
 *   <li>{@code squadHasLeadership}: a squad tem alguma linha pmr de liderança cadastrada (de qualquer pessoa).</li>
 * </ul>
 * ADMIN (e LEAD, só com app.membership.lead-level=legacy) não aparecem: são resolvidos antes, iguais nos dois modos.
 */
final class AuthzRules {

    /** Rótulos categóricos de motivo (log, métrica, authz-diff e relatório). Nunca texto livre. */
    static final String ONLY_ROSTER = "ONLY_ROSTER";
    static final String ONLY_SQUAD_ID = "ONLY_SQUAD_ID";
    static final String ROSTER_AND_SQUAD_ID = "ROSTER_AND_SQUAD_ID";
    static final String ONLY_JOB_TITLE = "ONLY_JOB_TITLE";
    static final String NO_LEADERSHIP_FALLBACK = "NO_LEADERSHIP_FALLBACK";
    static final String RESOLVER_ONLY = "RESOLVER_ONLY";
    static final String NEW_ONLY_MEMBERSHIP = "NEW_ONLY_MEMBERSHIP";

    static final String CHECK_MATCHES = "matchesSquad";
    static final String CHECK_MANAGE = "canManageSquad";
    static final String CHECK_LEADERSHIP = "isSquadLeadership";

    record Facts(boolean sessionSquad, boolean roster, boolean jobTitleLeadership,
                 boolean legacyLinked, boolean legacyLeader,
                 boolean newLinked, boolean newLeader,
                 boolean squadHasLeadership) { }

    private AuthzRules() {
    }

    // ----- regra legada (espelha SquadAccessService no modo legacy) -----

    static boolean legacyMatches(Facts f) {
        return f.sessionSquad() || f.legacyLinked() || f.roster();
    }

    static boolean legacyManage(Facts f) {
        return legacyMatches(f) && (f.jobTitleLeadership() || f.legacyLeader() || !f.squadHasLeadership());
    }

    static boolean legacyLeadership(Facts f) {
        return legacyMatches(f) && (f.jobTitleLeadership() || f.legacyLeader());
    }

    // ----- regra nova (modo new) -----

    static boolean newMatches(Facts f) {
        return f.newLinked();
    }

    /**
     * Liderança da squad no vínculo, ou (bootstrap de ambiente novo) squad sem NENHUMA liderança cadastrada e a pessoa
     * com vínculo nela.
     */
    static boolean newManage(Facts f) {
        return f.newLinked() && (f.newLeader() || !f.squadHasLeadership());
    }

    static boolean newLeadership(Facts f) {
        return f.newLinked() && f.newLeader();
    }

    static boolean decide(String check, Facts f, boolean modern) {
        return switch (check) {
            case CHECK_MATCHES -> modern ? newMatches(f) : legacyMatches(f);
            case CHECK_MANAGE -> modern ? newManage(f) : legacyManage(f);
            case CHECK_LEADERSHIP -> modern ? newLeadership(f) : legacyLeadership(f);
            default -> throw new IllegalArgumentException("check desconhecido: " + check);
        };
    }

    // ----- motivo categórico da divergência -----

    /**
     * @param legacyAllowed o que o legado decidiu (true = o novo NEGA o que o legado permite = perda de acesso)
     */
    static String reason(String check, Facts f, boolean legacyAllowed) {
        if (!legacyAllowed) {
            return NEW_ONLY_MEMBERSHIP;
        }
        return switch (check) {
            case CHECK_MATCHES -> matchesLossReason(f);
            case CHECK_MANAGE -> {
                if (f.jobTitleLeadership()) yield ONLY_JOB_TITLE;
                if (!f.newLinked() && !f.squadHasLeadership()) yield NO_LEADERSHIP_FALLBACK;
                yield RESOLVER_ONLY;
            }
            case CHECK_LEADERSHIP -> f.jobTitleLeadership() ? ONLY_JOB_TITLE : RESOLVER_ONLY;
            default -> RESOLVER_ONLY;
        };
    }

    /** De onde vinha o acesso que a regra nova não reconhece: users.squad_id, roster ou os dois. */
    static String matchesLossReason(Facts f) {
        if (f.sessionSquad() && f.roster()) return ROSTER_AND_SQUAD_ID;
        if (f.sessionSquad()) return ONLY_SQUAD_ID;
        if (f.roster()) return ONLY_ROSTER;
        return RESOLVER_ONLY;
    }
}
