package com.agilespace.backend.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Resposta de GET /api/admin/membership/authz-diff: autorização por squad LEGADA x NOVA para todos os usuários ativos x
 * squads (docs/membership-api.md, "authz-diff"). Somente leitura.
 *
 * @param mode                    {@code app.membership.authz} em vigor (a comparação é a mesma em qualquer modo)
 * @param usersChecked            usuários ativos comparados
 * @param usersSkippedPrivileged  ADMIN (e LEAD em lead-level=legacy; decisão idêntica nos dois modos; não entram na comparação)
 * @param usersFailed             usuários cujo cálculo lançou exceção (também vão para o log)
 * @param pairsChecked            pares (usuário, squad) avaliados
 * @param usersWithDivergence     usuários com ao menos uma divergência
 * @param totals                  divergências por sentido: {@code LOSS} (legado permite, novo nega) e {@code GAIN} (o contrário)
 * @param byCheck                 por ponto de decisão ({@code matchesSquad}, {@code canManageSquad}, {@code isSquadLeadership}) e sentido
 * @param byReason                por {@code SENTIDO:MOTIVO} (ex.: {@code LOSS:ONLY_ROSTER}); rótulos categóricos
 * @param sample                  até {@code sampleLimit} divergências, só ids e rótulos
 */
public record AuthzDiffDto(
        Instant generatedAt,
        String mode,
        int sampleLimit,
        int usersChecked,
        int usersSkippedPrivileged,
        int usersFailed,
        long pairsChecked,
        int usersWithDivergence,
        Map<String, Long> totals,
        Map<String, Map<String, Long>> byCheck,
        Map<String, Long> byReason,
        List<Sample> sample,
        boolean truncated) {

    /** Uma divergência. Sem e-mail nem nome: só o id da conta, a chave da squad e rótulos. */
    public record Sample(String userId, String squad, String check, String direction, String reason) { }
}
