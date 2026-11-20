-- Checklist "Guia de Elite" da tela inicial da Review: booleanos serializados em JSON.
ALTER TABLE public.showcase_sessions ADD COLUMN IF NOT EXISTS readiness_checklist text;
