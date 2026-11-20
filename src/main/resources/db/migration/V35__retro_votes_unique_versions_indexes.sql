-- Retro: integridade dos votos, trava otimista e índices.
--
-- ATENÇÃO EM PRODUÇÃO: o passo 1 REMOVE linhas duplicadas de retro_card_votes (mesmo card_id +
-- user_id, mantendo uma) e linhas com user_id nulo. Antes disso o servidor aceitava o mesmo
-- voto N vezes (cliente regravava a lista inteira), então a contagem de votos de cards antigos
-- pode diminuir. Rodar é idempotente.

-- 1. Dedupe de votos
DELETE FROM public.retro_card_votes WHERE user_id IS NULL;

DELETE FROM public.retro_card_votes v
USING (
    SELECT ctid AS row_id,
           ROW_NUMBER() OVER (PARTITION BY card_id, user_id ORDER BY ctid) AS rn
    FROM public.retro_card_votes
) d
WHERE v.ctid = d.row_id AND d.rn > 1;

ALTER TABLE public.retro_card_votes ALTER COLUMN user_id SET NOT NULL;

-- UNIQUE cobre também o índice por card_id (coluna líder).
CREATE UNIQUE INDEX IF NOT EXISTS uq_retro_card_votes_card_user
    ON public.retro_card_votes (card_id, user_id);

-- 2. Trava otimista (@Version em RetroBoard/RetroCard). Linhas existentes começam na versão 0.
ALTER TABLE public.retro_boards ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
ALTER TABLE public.retro_cards  ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;

-- 3. Índices de leitura
CREATE INDEX IF NOT EXISTS idx_retro_cards_board_order
    ON public.retro_cards (board_id, card_order);
CREATE INDEX IF NOT EXISTS idx_retro_participants_board
    ON public.retro_participants (board_id);
CREATE INDEX IF NOT EXISTS idx_retro_board_columns_board
    ON public.retro_board_columns (board_id);
CREATE INDEX IF NOT EXISTS idx_retro_card_original_texts_card
    ON public.retro_card_original_texts (card_id);
CREATE INDEX IF NOT EXISTS idx_retro_chat_board_channel_created
    ON public.retro_chat_messages (board_id, channel_id, created_at DESC, id DESC);
