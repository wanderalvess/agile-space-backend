-- Prepara o schema pro motor de sync do Squad rodar no backend (SquadSyncService)
-- em vez de client-side (useSquadStore.ts). Ver plano de unificação Squad Pulse +
-- jiradash, Fase 2.

-- squads: 3 colunas que o sync já tentava gravar via updates parciais, mas que
-- nunca existiram na entidade Postgres (silenciosamente descartadas pelo Jackson).
ALTER TABLE squads
    ADD COLUMN IF NOT EXISTS last_sync_by character varying(255),
    ADD COLUMN IF NOT EXISTS last_sync_issue_count integer,
    ADD COLUMN IF NOT EXISTS last_sync_error text;

-- squad_metrics_rollup: a PK era só squad_id ("apenas 1 rollup ativo por squad"),
-- mas o sync grava um rollup POR SPRINT (partição) a cada rodada — cada sprint
-- nova sobrescrevia a anterior, e não havia como guardar/comparar sprints
-- passadas. Migra pra PK composta {squad_id}_{sprint_id}, mesma convenção já
-- usada em squad_members/squad_issue_snapshots/squad_issue_worklog_cache.
ALTER TABLE squad_metrics_rollup
    ADD COLUMN IF NOT EXISTS db_id character varying(255);

UPDATE squad_metrics_rollup
    SET db_id = squad_id || '_' || COALESCE(NULLIF(sprint_id, ''), 'UNMAPPED')
    WHERE db_id IS NULL;

ALTER TABLE squad_metrics_rollup ALTER COLUMN db_id SET NOT NULL;

DO $$ BEGIN
    ALTER TABLE squad_metrics_rollup DROP CONSTRAINT squad_metrics_rollup_pkey;
EXCEPTION WHEN undefined_object THEN NULL; END $$;

DO $$ BEGIN
    ALTER TABLE squad_metrics_rollup ADD CONSTRAINT squad_metrics_rollup_pkey PRIMARY KEY (db_id);
EXCEPTION WHEN duplicate_table OR duplicate_object THEN NULL; END $$;
