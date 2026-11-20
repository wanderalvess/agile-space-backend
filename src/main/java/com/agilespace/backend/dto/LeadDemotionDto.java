package com.agilespace.backend.dto;

import java.util.List;

/**
 * Resposta de {@code POST /api/admin/membership/lead-demotion} (Etapa 4; contrato em docs/membership-api.md).
 *
 * @param mode     DRY_RUN (nada gravado) ou APPLIED
 * @param leadLevel app.membership.lead-level em vigor: "off" ou "legacy"
 * @param limit    tamanho do lote (ignorado quando há userId)
 */
public record LeadDemotionDto(String mode, String leadLevel, int limit, Summary summary, List<Item> users, List<String> warnings) {

    public static final String MODE_DRY_RUN = "DRY_RUN";
    public static final String MODE_APPLIED = "APPLIED";

    public static final String HAS_LEADERSHIP_LINK = "HAS_LEADERSHIP_LINK";
    public static final String NO_LEADERSHIP_LINK = "NO_LEADERSHIP_LINK";

    public static final String WOULD_DEMOTE = "WOULD_DEMOTE";
    public static final String WOULD_DEMOTE_FORCED = "WOULD_DEMOTE_FORCED";
    public static final String DEMOTED = "DEMOTED";
    public static final String DEMOTED_FORCED = "DEMOTED_FORCED";
    public static final String KEPT_NO_LEADERSHIP_LINK = "KEPT_NO_LEADERSHIP_LINK";
    public static final String OVER_LIMIT = "OVER_LIMIT";

    public record Summary(int leadUsers, int hasLeadershipLink, int noLeadershipLink, int wouldDemote, int demoted,
                          int keptNoLeadershipLink, int overLimit) { }

    /**
     * @param leadershipSquads squads com linha de liderança (MAIÚSCULAS, sem repetição, ordenadas)
     * @param activeApiKeys    chaves de API ativas (não revogadas e não vencidas) do usuário
     * @param apiKeysNote      aviso sobre as chaves (null quando não há chave ativa)
     */
    public record Item(String userId, String email, String name, boolean active, String classification,
                       List<String> leadershipSquads, String action, long activeApiKeys, String apiKeysNote) { }
}
