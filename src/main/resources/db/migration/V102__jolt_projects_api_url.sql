-- Jolt: URL de origem do layout (campo do Sandbox, antes guardado só no navegador). Idempotente, não remove dados.
ALTER TABLE public.jolt_projects ADD COLUMN IF NOT EXISTS api_url text;
