-- Trava otimista da sala de Poker (@Version em PokerRoom). Salas existentes começam na versão 0.
-- Idempotente: no-op onde a coluna já existe.
ALTER TABLE public.poker_rooms ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
