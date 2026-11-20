-- Sala assíncrona: um participante vota em várias tarefas, então a unicidade (sala, participante) cai.
-- O id do voto passa a ser room_participant (sync) ou room_participant_issue (async), e a unicidade
-- real vira (sala, participante, tarefa). Idempotente.
ALTER TABLE public.poker_votes DROP CONSTRAINT IF EXISTS poker_votes_room_id_participant_id_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_poker_votes_room_participant_issue
    ON public.poker_votes (room_id, participant_id, COALESCE(issue_id, ''));

-- Histórico de rodadas é sempre lido por sala.
CREATE INDEX IF NOT EXISTS idx_poker_rounds_room_id ON public.poker_rounds (room_id);
