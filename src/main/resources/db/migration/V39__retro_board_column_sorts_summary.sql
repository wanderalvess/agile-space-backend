-- Retro: persiste "ordenar por votos" por coluna (column_sorts, JSON {colId: bool}) e o resumo
-- sincronizado (summary, JSON livre). Nullable e idempotente.
ALTER TABLE public.retro_boards ADD COLUMN IF NOT EXISTS column_sorts text;
ALTER TABLE public.retro_boards ADD COLUMN IF NOT EXISTS summary text;
