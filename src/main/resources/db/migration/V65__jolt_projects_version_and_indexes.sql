-- Jolt: lock otimista dos projetos salvos (@Version) e índices das consultas de listagem/versões.
-- Idempotente. Não remove dados.
ALTER TABLE public.jolt_projects ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_jolt_projects_author ON public.jolt_projects (author_id);
CREATE INDEX IF NOT EXISTS idx_jolt_projects_squad ON public.jolt_projects (squad_id);
CREATE INDEX IF NOT EXISTS idx_jolt_project_versions_project ON public.jolt_project_versions (project_id, version_number DESC);
