-- Limite de votos por participante (dot-voting) no retro. Null/0 = sem limite.
-- Default 5 também pros boards já existentes.

ALTER TABLE retro_boards
    ADD COLUMN IF NOT EXISTS max_votes_per_participant INTEGER DEFAULT 5;
