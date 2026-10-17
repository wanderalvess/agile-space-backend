-- Brainstorming: encerrar a sessão, facilitação transferível, limite de votos por pessoa e marca "editada" na ideia.
-- Só aditiva e idempotente: sem DELETE/DROP/UPDATE; tudo nulo nas linhas existentes (status nulo = OPEN; facilitator_id nulo =
-- o criador; votes_per_participant nulo = sem limite). Contrato: docs/ceremony-brainstorming-api.md.
ALTER TABLE brainstorming_boards ADD COLUMN IF NOT EXISTS status VARCHAR(20);
ALTER TABLE brainstorming_boards ADD COLUMN IF NOT EXISTS finished_at VARCHAR(40);
ALTER TABLE brainstorming_boards ADD COLUMN IF NOT EXISTS facilitator_id VARCHAR(255);
ALTER TABLE brainstorming_boards ADD COLUMN IF NOT EXISTS votes_per_participant INTEGER;

ALTER TABLE brainstorming_ideas ADD COLUMN IF NOT EXISTS edited_at VARCHAR(40);
ALTER TABLE brainstorming_ideas ADD COLUMN IF NOT EXISTS edited_by VARCHAR(255);
