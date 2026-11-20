-- Índices de consulta do Squad Hub. As tabelas só tinham a chave primária e toda leitura filtra por squad
-- (e, na maioria, por sprint ou pessoa). Só cria índices (IF NOT EXISTS); não altera nem apaga dados.

CREATE INDEX IF NOT EXISTS idx_squad_issue_snapshots_squad_sprint
    ON squad_issue_snapshots (squad_id, sprint_id);

CREATE INDEX IF NOT EXISTS idx_squad_issue_snapshots_squad_assignee
    ON squad_issue_snapshots (squad_id, assignee_id);

CREATE INDEX IF NOT EXISTS idx_squad_issue_worklog_cache_squad_sprint
    ON squad_issue_worklog_cache (squad_id, sprint_id);

CREATE INDEX IF NOT EXISTS idx_squad_member_metrics_squad
    ON squad_member_metrics (squad_id);

CREATE INDEX IF NOT EXISTS idx_squad_daily_snapshots_squad_date
    ON squad_daily_snapshots (squad_id, snapshot_date);

CREATE INDEX IF NOT EXISTS idx_squad_metrics_rollup_squad_sprint
    ON squad_metrics_rollup (squad_id, sprint_id);

CREATE INDEX IF NOT EXISTS idx_squad_panels_squad
    ON squad_panels (squad_id);

CREATE INDEX IF NOT EXISTS idx_squad_person_configs_lookup
    ON squad_person_configs (squad_id, sprint_id, jira_account_id);
