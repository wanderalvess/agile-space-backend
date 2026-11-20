-- Capa da Review: URL do Drive/Dropbox com query passa fácil de 255 caracteres e derrubava o save da sessão inteira.
-- Idempotente (ALTER TYPE para text repetido é no-op).
ALTER TABLE public.showcase_sessions ALTER COLUMN cover_image TYPE text;

-- A listagem do hub filtra por LOWER(squad_name) e ordena por created_at.
CREATE INDEX IF NOT EXISTS idx_showcase_sessions_squad_lower
    ON public.showcase_sessions (LOWER(squad_name), created_at DESC);
