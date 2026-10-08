-- Links de apoio do card da Review: documento técnico e página do TDN.
-- Inferidos na importação do Jira e editáveis no card. Idempotente.
ALTER TABLE public.showcase_tasks ADD COLUMN IF NOT EXISTS tech_doc_url text;
ALTER TABLE public.showcase_tasks ADD COLUMN IF NOT EXISTS tdn_url text;
