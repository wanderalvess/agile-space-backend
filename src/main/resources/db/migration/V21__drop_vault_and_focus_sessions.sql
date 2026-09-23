-- Vault e Modo Foco (UserFocusSession) saem do sistema novo por decisão de produto —
-- continuam existindo no legado; se algum time reportar uso real, a feature volta.
-- Sem dado real em produção ainda (times usando o legado), então é drop direto.

DROP TABLE IF EXISTS vault_secrets;
DROP TABLE IF EXISTS user_focus_sessions;
