-- Papel (DEV/QA) e capacidade por pessoa/sprint, com herança sprint atual -> sprint
-- anterior -> default do squad (GLOBAL_SPRINT_ID). Ver plano de unificação Squad Pulse +
-- jiradash, Fase 6.
CREATE TABLE IF NOT EXISTS squad_person_configs (
    db_id character varying(255) NOT NULL,
    squad_id character varying(255) NOT NULL,
    sprint_id character varying(255) NOT NULL,
    jira_account_id character varying(255) NOT NULL,
    papel character varying(10),
    dias_codificacao_teste integer,
    dias_regressivo integer,
    horas_produtivas double precision,
    updated_at character varying(255)
);

DO $$ BEGIN
    ALTER TABLE ONLY squad_person_configs ADD CONSTRAINT squad_person_configs_pkey PRIMARY KEY (db_id);
EXCEPTION WHEN duplicate_table OR duplicate_object THEN NULL; END $$;
