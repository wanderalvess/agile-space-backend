-- Radar de clima e Retrospectiva: encerramento real, etapa da apresentação e texto livre do check-in.
-- Só aditiva e idempotente: sem DELETE/DROP/UPDATE; linhas existentes ficam com tudo nulo (nenhuma data é inventada;
-- o status do Radar continua vindo de health_check_boards.status e a Retro antiga é tratada como aberta).
-- Contrato: docs/ceremony-radar-retro-api.md.
ALTER TABLE health_check_boards ADD COLUMN IF NOT EXISTS finished_at VARCHAR(50);
ALTER TABLE health_check_boards ADD COLUMN IF NOT EXISTS presentation_stage VARCHAR(40);

ALTER TABLE retro_boards ADD COLUMN IF NOT EXISTS finished_at TIMESTAMP(6) WITHOUT TIME ZONE;
ALTER TABLE retro_boards ADD COLUMN IF NOT EXISTS lifecycle VARCHAR(20);

ALTER TABLE retro_participants ADD COLUMN IF NOT EXISTS checkin_note VARCHAR(500);
